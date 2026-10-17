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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link JsonFiles#writeAtomic} 的三条关键性质：内容正确、覆盖是整体替换、不留临时文件，
 * 以及写入失败时目标文件保持原样。
 *
 * <p>这些性质是「配置与歌单不再被崩溃写坏」的实现基础：{@code ConfigManager.saveConfig()} 与
 * {@code PlaylistService.savePlaylists()} 都经由本方法落盘。</p>
 */
class JsonFilesTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void writesNewFileAndRoundTrips(@TempDir Path tempDir) throws Exception {
        File target = tempDir.resolve("playlists.json").toFile();
        Playlist playlist = new Playlist("我的最爱", "测试歌单");
        playlist.addMusic("song-1");

        JsonFiles.writeAtomic(objectMapper.writerWithDefaultPrettyPrinter(), target, List.of(playlist));

        assertTrue(target.isFile(), "目标文件应被创建");
        List<Playlist> loaded = objectMapper.readValue(target,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Playlist.class));
        assertEquals(1, loaded.size());
        assertEquals("我的最爱", loaded.get(0).getName());
        assertEquals(List.of("song-1"), loaded.get(0).getMusicIds());
    }

    @Test
    void replacesExistingContentEntirely(@TempDir Path tempDir) throws Exception {
        File target = tempDir.resolve("hellomusic-config.json").toFile();
        objectMapper.writeValue(target, Map.of("serverName", "OLD-NAME-SHOULD-VANISH"));

        JsonFiles.writeAtomic(objectMapper.writerWithDefaultPrettyPrinter(), target, Map.of("serverName", "new"));

        String json = Files.readString(target.toPath());
        assertFalse(json.contains("OLD-NAME-SHOULD-VANISH"),
                "旧内容不应残留（说明是整体替换，而不是拼接/截断不完整）: " + json);
        assertEquals("new", objectMapper.readValue(target, Map.class).get("serverName"));
    }

    @Test
    void leavesNoTemporaryFilesBehind(@TempDir Path tempDir) throws Exception {
        File target = tempDir.resolve("playlists.json").toFile();

        JsonFiles.writeAtomic(objectMapper.writerWithDefaultPrettyPrinter(), target,
                List.of(new Playlist("第一次", "")));
        JsonFiles.writeAtomic(objectMapper.writerWithDefaultPrettyPrinter(), target,
                List.of(new Playlist("第二次", "")));

        try (Stream<Path> entries = Files.list(tempDir)) {
            List<String> names = entries.map(path -> path.getFileName().toString()).sorted().toList();
            assertEquals(List.of("playlists.json"), names,
                    "目录中应只剩目标文件，临时文件必须被清理: " + names);
        }
    }

    @Test
    void failedWriteKeepsOriginalFileAndCleansUp(@TempDir Path tempDir) throws Exception {
        File target = tempDir.resolve("hellomusic-config.json").toFile();
        objectMapper.writeValue(target, Map.of("serverName", "原值"));

        assertThrows(Exception.class, () -> JsonFiles.writeAtomic(
                objectMapper.writerWithDefaultPrettyPrinter(), target, new ExplodingValue()));

        assertEquals("原值", objectMapper.readValue(target, Map.class).get("serverName"),
                "写入失败时目标文件必须保持原样（临时文件还没被替换过去）");
        try (Stream<Path> entries = Files.list(tempDir)) {
            List<String> names = entries.map(path -> path.getFileName().toString()).sorted().toList();
            assertEquals(List.of("hellomusic-config.json"), names,
                    "写入失败后也应清理掉临时文件: " + names);
        }
    }

    /** 序列化时必定抛异常的夹具，用于制造写入失败。 */
    public static class ExplodingValue {
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }
}
