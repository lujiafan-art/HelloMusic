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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@link MetadataExtractor} 的「解析失败 → 用文件名兜底」这条路径。
 * 这里刻意用无法解析出标签的空文件，因此断言的是兜底逻辑本身。
 */
class MetadataExtractorTest {

    @Test
    void fallsBackToArtistAndTitleFromFileName(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("周杰伦 - 稻香.mp3").toFile();
        Files.write(file.toPath(), new byte[0]);

        MusicMetadata metadata = MetadataExtractor.extract(file);

        assertEquals("稻香", metadata.getTitle());
        assertEquals("周杰伦", metadata.getArtist());
    }

    @Test
    void usesWholeBaseNameAsTitleWhenNoSeparator(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("晴天.mp3").toFile();
        Files.write(file.toPath(), new byte[0]);

        MusicMetadata metadata = MetadataExtractor.extract(file);

        assertEquals("晴天", metadata.getTitle());
        assertEquals("Unknown Artist", metadata.getArtist());
    }

    @Test
    void keepsRemainderAsTitleWhenFileNameHasMultipleSeparators(@TempDir Path tempDir) throws Exception {
        // 修复「A - B - C.mp3」标题被截断成 "B"：多段时应把第 2 段起重新拼回标题
        File file = tempDir.resolve("周杰伦 - 范特西 - 简单爱.mp3").toFile();
        Files.write(file.toPath(), new byte[0]);

        MusicMetadata metadata = MetadataExtractor.extract(file);

        assertEquals("周杰伦", metadata.getArtist(), "首段仍是艺术家");
        assertEquals("范特西 - 简单爱", metadata.getTitle(), "多段标题不应被截断");
    }

    @Test
    void neverReturnsNullAndKeepsUnknownPlaceholdersForUnreadableFile(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("broken.flac").toFile();
        Files.write(file.toPath(), new byte[]{0, 1, 2, 3});

        MusicMetadata metadata = MetadataExtractor.extract(file);

        assertNotNull(metadata);
        assertEquals("broken", metadata.getTitle(), "无标签时标题应回退为文件名");
        assertEquals("Unknown Artist", metadata.getArtist());
        assertEquals(0, metadata.getDuration());
        assertTrue(metadata.getAlbum() != null && !metadata.getAlbum().isEmpty());
    }

    @Test
    void coverAndLyricsCacheReturnNullWithoutLeakingSentinel(@TempDir Path tempDir) throws Exception {
        // 回归：封面/歌词缓存用哨兵值标记「无结果」，命中后必须转换回 null，
        // 不得把哨兵误当作真实封面/歌词返回给调用方（那会导致 /cover 返回空 body）。
        File file = tempDir.resolve("plain.mp3").toFile();
        Files.write(file.toPath(), new byte[0]);

        assertNull(MetadataExtractor.extractCover(file), "无封面文件应返回 null");
        assertNull(MetadataExtractor.extractLyrics(file), "无歌词文件应返回 null");
        // 第二次调用命中缓存路径，仍应返回 null（哨兵 → null 转换正确）
        assertNull(MetadataExtractor.extractCover(file), "缓存命中后无封面仍应返回 null");
        assertNull(MetadataExtractor.extractLyrics(file), "缓存命中后无歌词仍应返回 null");
    }
}
