/*
 * HelloMusic - Local music server with UDP broadcast and REST API
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

import java.io.File;
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
            if (isUnknown(metadata.getTitle(), UNKNOWN_TITLE) && !parts[1].trim().isEmpty()) {
                metadata.setTitle(parts[1].trim());
            }
        } else if (isUnknown(metadata.getTitle(), UNKNOWN_TITLE)) {
            metadata.setTitle(baseName);
        }
    }
}
