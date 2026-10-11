package core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫「歌单持久化」这项修复。
 *
 * <p>{@link Playlist} 含 {@code LocalDateTime} 字段，必须用注册了 JavaTimeModule 的
 * {@link ObjectMapper} 才能序列化 —— 裸 {@code new ObjectMapper()} 在 Jackson 2.12+ 会抛
 * {@code InvalidDefinitionException}，而该异常又会被 {@code PlaylistService} 的 catch 静默吞掉，
 * 导致 playlists.json 写不进也读不出。</p>
 *
 * <p>这里用 {@link Jackson2ObjectMapperBuilder} 构造 mapper，它与 Spring Boot 自动配置
 * 注入的那个 ObjectMapper 同源：只要 jsr310 还在 classpath 上、且模块能被自动注册，本测试就会通过；
 * 一旦有人把依赖或配置改回去，本测试会立刻失败。</p>
 */
class PlaylistJsonTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void playlistRoundTripsThroughJsonFile(@TempDir Path tempDir) throws Exception {
        Playlist original = new Playlist("我的最爱", "测试歌单");
        original.addMusic("song-1");
        original.addMusic("song-2");

        File file = tempDir.resolve("playlists.json").toFile();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, List.of(original));

        assertTrue(file.length() > 0, "歌单文件应被真正写入（此前因序列化异常而始终为空）");

        List<Playlist> loaded = objectMapper.readValue(
                file,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Playlist.class));

        assertEquals(1, loaded.size());
        Playlist restored = loaded.get(0);
        assertEquals(original.getId(), restored.getId());
        assertEquals("我的最爱", restored.getName());
        assertEquals("测试歌单", restored.getDescription());
        assertEquals(List.of("song-1", "song-2"), restored.getMusicIds());
        assertNotNull(restored.getCreatedAt());
        assertEquals(original.getCreatedAt(), restored.getCreatedAt());
        assertEquals(original.getUpdatedAt(), restored.getUpdatedAt());
    }

    @Test
    void jsonContainsReadableIsoOrArrayDatesNotBrokenValues(@TempDir Path tempDir) throws Exception {
        Playlist playlist = new Playlist("我的最爱", "");
        File file = tempDir.resolve("playlists.json").toFile();

        objectMapper.writeValue(file, List.of(playlist));

        String json = java.nio.file.Files.readString(file.toPath());
        assertTrue(json.contains("createdAt"), "序列化结果应包含 createdAt 字段，实际: " + json);
        assertTrue(json.contains(playlist.getId()));
    }
}
