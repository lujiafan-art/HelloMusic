/*
 * HelloMusic - Local music server with Unix socket control plane and HTTP media stream
 * Copyright (C) 2026 Lu Jiafan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package core;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音频元数据提取器（基于 Jaudiotagger）。
 *
 * <p>一次覆盖 MP3（ID3v1 / ID3v2，正确识别 UTF-8、GBK 等编码，<b>中文标签不再乱码</b>）、
 * FLAC、M4A/MP4、OGG Vorbis、WAV 等格式，并同时读取时长与比特率。</p>
 *
 * <p>相比原先手写解析仅 MP3 的 ID3v1 标签、且用 {@code ISO-8859-1} 解码的实现，
 * 这里能正确解析 ID3v2 与各格式的原生标签。任何读取失败都不会中断扫描，
 * 而是回退到「从文件名解析」的兜底策略。</p>
 */
public final class MetadataExtractor {

    private static final Pattern LEADING_INT = Pattern.compile("\\d+");

    private static final String UNKNOWN_TITLE = "Unknown Title";
    private static final String UNKNOWN_ARTIST = "Unknown Artist";

    /** 封面图读取大小上限（10MB）：超大的同目录/内嵌封面直接跳过，避免读进内存。 */
    private static final long MAX_COVER_BYTES = 10L * 1024 * 1024;
    /** 歌词文件读取大小上限（2MB）。 */
    private static final long MAX_LYRICS_BYTES = 2L * 1024 * 1024;

    /**
     * 封面 / 歌词的按需提取缓存（绝对路径 → 结果）。
     *
     * <p>封面与歌词是「按需」提取、不进曲库缓存，因此每次 {@code /cover}/{@code /lyrics}
     * 都会重新 {@code AudioFileIO.read} 整个文件并读盘。这里加一层有界 LRU，命中后直接复用上次
     * 结果，避免高并发下的重复磁盘 IO 爆炸。负结果也缓存（用哨兵值区分），使「无封面/无歌词」
     * 的文件同样不重复解析。</p>
     *
     * <p>缓存只活在本进程内存里、随进程消失，故无需处理过期或损坏；代价是运行期间若封面/歌词
     * 文件被外部改动，需重启才会反映新值——对封面/歌词这类极少变动的资源可以接受。</p>
     */
    private static final Map<String, Cover> COVER_CACHE = synchronizedLru(16);
    private static final Map<String, String> LYRICS_CACHE = synchronizedLru(32);
    /** 「无封面」哨兵：真实封面 {@code data} 非空，据此与哨兵区分。 */
    private static final Cover NO_COVER = new Cover(new byte[0], "");
    /** 「无歌词」哨兵：真实歌词不会是 NUL 控制字符序列，据此与哨兵区分。 */
    private static final String NO_LYRICS = "\u0000";

    static {
        // Jaudiotagger 内部使用 java.util.logging，默认会输出大量解析警告，这里静音
        Logger.getLogger("org.jaudiotagger").setLevel(Level.SEVERE);
    }

    private MetadataExtractor() {
    }

    /**
     * 提取音频文件的元数据：标题、艺术家、专辑、流派、年份、音轨号、注释、时长、比特率。
     * 标签中缺失的标题/艺术家会用文件名兜底。
     *
     * @param file 音频文件
     * @return 元数据对象（永不为 {@code null}）
     */
    public static MusicMetadata extract(File file) {
        MusicMetadata metadata = new MusicMetadata();

        try {
            AudioFile audioFile = AudioFileIO.read(file);

            // 1) 音频头信息：时长与比特率，对所有支持的格式都有效
            AudioHeader header = audioFile.getAudioHeader();
            if (header != null) {
                metadata.setDuration(header.getTrackLength());
                metadata.setBitrate(parseBitrate(header.getBitRate()));
            }

            // 2) 标签字段（各 setter 对空值有默认兜底）
            Tag tag = audioFile.getTag();
            if (tag != null) {
                metadata.setTitle(firstField(tag, FieldKey.TITLE));
                metadata.setArtist(firstField(tag, FieldKey.ARTIST));
                metadata.setAlbum(firstField(tag, FieldKey.ALBUM));
                metadata.setGenre(firstField(tag, FieldKey.GENRE));
                metadata.setComment(firstField(tag, FieldKey.COMMENT));
                metadata.setYear(parseLeadingInt(firstField(tag, FieldKey.YEAR)));
                metadata.setTrackNumber(parseLeadingInt(firstField(tag, FieldKey.TRACK)));
            }
        } catch (Exception e) {
            // 读取失败（格式不支持 / 文件损坏）：落到下面的文件名兜底
        }

        // 3) 兜底：标签里没拿到标题或艺术家时，尝试解析「艺术家 - 歌曲名」文件名
        if (isUnknown(metadata.getTitle(), UNKNOWN_TITLE)
                || isUnknown(metadata.getArtist(), UNKNOWN_ARTIST)) {
            applyFileNameFallback(file, metadata);
        }

        // 4) 兜底：MP3 若 Jaudiotagger 未能算出时长，退回自研解析器
        if (metadata.getDuration() <= 0 && file.getName().toLowerCase().endsWith(".mp3")) {
            try {
                metadata.setDuration(Mp3DurationParser.getDuration(file));
            } catch (Exception ignored) {
                // 时长解析失败时保持 0
            }
        }

        return metadata;
    }

    /** 安全读取单个标签字段；不存在或格式不支持时返回 {@code null}。 */
    private static String firstField(Tag tag, FieldKey key) {
        try {
            String value = tag.getFirst(key);
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析字符串开头的整数（用于 "2020"、"2020-05-01"、"3/12" 等）。 */
    private static int parseLeadingInt(String value) {
        if (value == null) {
            return 0;
        }
        Matcher matcher = LEADING_INT.matcher(value);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (NumberFormatException ignored) {
                // 数值过大时忽略
            }
        }
        return 0;
    }

    /** 解析比特率字符串（如 "320"）为整数，失败返回 0。 */
    private static int parseBitrate(String bitRate) {
        if (bitRate == null) {
            return 0;
        }
        try {
            return Integer.parseInt(bitRate.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isUnknown(String value, String unknownPlaceholder) {
        return value == null || value.isEmpty() || unknownPlaceholder.equals(value);
    }

    /** 从「艺术家 - 歌曲名」格式的文件名补全缺失的标题/艺术家，不覆盖已有的有效标签。 */
    private static void applyFileNameFallback(File file, MusicMetadata metadata) {
        String fileName = file.getName();
        int extIndex = fileName.lastIndexOf('.');
        String baseName = extIndex > 0 ? fileName.substring(0, extIndex) : fileName;

        String[] parts = baseName.split(" - ");
        if (parts.length >= 2) {
            if (isUnknown(metadata.getArtist(), UNKNOWN_ARTIST) && !parts[0].trim().isEmpty()) {
                metadata.setArtist(parts[0].trim());
            }
            if (isUnknown(metadata.getTitle(), UNKNOWN_TITLE)) {
                // 两段：artist - title；多段（如 artist - album - title）把第 2 段起重新拼回标题，
                // 避免「A - B - C.mp3」的标题被截断成只剩 "B"。
                String title = parts.length == 2
                        ? parts[1].trim()
                        : String.join(" - ", Arrays.copyOfRange(parts, 1, parts.length)).trim();
                if (!title.isEmpty()) {
                    metadata.setTitle(title);
                }
            }
        } else if (isUnknown(metadata.getTitle(), UNKNOWN_TITLE)) {
            metadata.setTitle(baseName);
        }
    }

    /** 内嵌封面：图片字节 + MIME 类型。 */
    public record Cover(byte[] data, String mime) {}

    /**
     * 提取封面图：优先读音频内嵌的封面（APIC / artwork），回退到同目录同名图片
     * （{@code <原名>.jpg/.jpeg/.png}）。两者都没有时返回 {@code null}。
     *
     * <p>封面提取是「按需」操作，不在扫描阶段调用，避免把大块图片字节塞进内存中的
     * {@link MusicFile} 缓存、拖慢启动扫描。结果做进程内 LRU 缓存，重复请求不再反复读盘。</p>
     */
    public static Cover extractCover(File file) {
        String key = cacheKey(file);
        Cover cached = COVER_CACHE.get(key);
        if (cached != null) {
            return cached == NO_COVER ? null : cached;
        }
        Cover cover = extractCoverUncached(file);
        COVER_CACHE.put(key, cover != null ? cover : NO_COVER);
        return cover;
    }

    /** 未经缓存的封面提取逻辑（见 {@link #extractCover}）。 */
    private static Cover extractCoverUncached(File file) {
        try {
            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTag();
            if (tag != null) {
                Artwork artwork = tag.getFirstArtwork();
                if (artwork != null) {
                    byte[] data = artwork.getBinaryData();
                    if (data != null && data.length > 0 && data.length <= MAX_COVER_BYTES) {
                        String mime = artwork.getMimeType();
                        if (mime == null || mime.isEmpty()) {
                            mime = "image/jpeg";
                        }
                        return new Cover(data, mime);
                    }
                }
            }
        } catch (Exception ignored) {
            // 读取内嵌封面失败：继续尝试同目录图片
        }
        // 回退：同目录同名封面图片
        String base = baseNameOf(file);
        for (String ext : new String[]{"jpg", "jpeg", "png"}) {
            File sidecar = new File(file.getParent(), base + "." + ext);
            if (sidecar.isFile() && sidecar.length() <= MAX_COVER_BYTES) {
                try {
                    byte[] data = Files.readAllBytes(sidecar.toPath());
                    String mime = ext.equals("png") ? "image/png" : "image/jpeg";
                    return new Cover(data, mime);
                } catch (IOException ignored) {
                    // 尝试下一种扩展名
                }
            }
        }
        return null;
    }

    /**
     * 提取歌词：优先读音频内嵌歌词（USLT / SYLT），回退到同目录同名 {@code .lrc} 文本文件。
     * 两者都没有时返回 {@code null}。结果做进程内 LRU 缓存，重复请求不再反复读盘。
     */
    public static String extractLyrics(File file) {
        String key = cacheKey(file);
        String cached = LYRICS_CACHE.get(key);
        if (cached != null) {
            return cached == NO_LYRICS ? null : cached;
        }
        String lyrics = extractLyricsUncached(file);
        LYRICS_CACHE.put(key, lyrics != null ? lyrics : NO_LYRICS);
        return lyrics;
    }

    /** 未经缓存的歌词提取逻辑（见 {@link #extractLyrics}）。 */
    private static String extractLyricsUncached(File file) {
        try {
            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTag();
            if (tag != null) {
                String lyrics = firstField(tag, FieldKey.LYRICS);
                if (lyrics != null && !lyrics.trim().isEmpty()) {
                    return lyrics;
                }
            }
        } catch (Exception ignored) {
            // 读取内嵌歌词失败：继续尝试同目录 .lrc
        }
        // 回退：同目录同名 .lrc 文件
        File lrc = new File(file.getParent(), baseNameOf(file) + ".lrc");
        if (lrc.isFile() && lrc.length() <= MAX_LYRICS_BYTES) {
            try {
                return decodeLyrics(Files.readAllBytes(lrc.toPath()));
            } catch (IOException ignored) {
                // 读取失败
            }
        }
        return null;
    }

    /**
     * 解码歌词字节：优先按 UTF-8 严格解码，失败再回退 GBK。
     *
     * <p>中文歌词常见 GBK 编码，硬编码 UTF-8 会乱码；这里做一次「UTF-8 严格 → GBK」的双通道探测，
     * 覆盖绝大多数情况，避免 {@code .lrc} 出现乱码。</p>
     */
    private static String decodeLyrics(byte[] bytes) {
        try {
            CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return utf8.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // 不是合法 UTF-8：按 GBK 解码
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception ex) {
                // GBK 也不可用：退化 UTF-8（可能仍乱码，但不会抛异常）
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    /** 去掉扩展名的文件名（用于同目录封面 / 歌词兜底）。 */
    private static String baseNameOf(File file) {
        String name = file.getName();
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    /** 有界 LRU（访问序）并套线程安全包装：封面/歌词缓存由多个 Tomcat 请求线程共享。 */
    private static <V> Map<String, V> synchronizedLru(int maxEntries) {
        LinkedHashMap<String, V> lru = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > maxEntries;
            }
        };
        return Collections.synchronizedMap(lru);
    }

    /** 缓存 key：优先规范化路径（解析软链），失败回退绝对路径。 */
    private static String cacheKey(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    /** 清空封面/歌词缓存（测试 / 调试用）。 */
    public static void clearCaches() {
        COVER_CACHE.clear();
        LYRICS_CACHE.clear();
    }
}
