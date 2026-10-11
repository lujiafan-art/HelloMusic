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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
