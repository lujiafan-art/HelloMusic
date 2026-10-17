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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class PlaylistService {
    private final Map<String, Playlist> playlists = new ConcurrentHashMap<>();
    private final MusicScannerService scannerService;
    private static final String PLAYLIST_FILE = "playlists.json";
    private final ObjectMapper objectMapper;

    /**
     * 注入 Spring 托管的 {@link ObjectMapper}，而不是自行 {@code new ObjectMapper()}。
     *
     * <p>Playlist 含 {@code LocalDateTime} 字段，而裸 ObjectMapper 未注册 JavaTimeModule，
     * Jackson 2.12+ 会直接抛 {@code InvalidDefinitionException}（
     * "Java 8 date/time type java.time.LocalDateTime not supported by default"）。
     * 该异常属于 IOException，会被下面的 catch 静默吞掉，导致 playlists.json 既写不进也读不出。
     * Spring Boot 自动配置的 ObjectMapper 已注册 jsr310 模块，可正确序列化。</p>
     */
    @Autowired
    public PlaylistService(MusicScannerService scannerService, ObjectMapper objectMapper) {
        this.scannerService = scannerService;
        this.objectMapper = objectMapper;
        loadPlaylists();
    }

    // 加载播放列表（从文件）
    private void loadPlaylists() {
        File file = new File(PLAYLIST_FILE);
        if (file.exists()) {
            try {
                List<Playlist> list = objectMapper.readValue(
                        file,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Playlist.class)
                );
                list.forEach(p -> playlists.put(p.getId(), p));
                System.out.println("📋 Loaded " + playlists.size() + " playlists from " + PLAYLIST_FILE);
            } catch (IOException e) {
                System.err.println("❌ Failed to load playlists: " + e.getMessage());
            }
        }
    }

    // 保存播放列表（到文件）
    // synchronized：多个请求并发改歌单时，避免两个写线程互相覆盖；
    // 配合 JsonFiles 的原子替换，落盘内容要么是旧版本、要么是完整的新版本
    private synchronized void savePlaylists() {
        try {
            List<Playlist> list = new ArrayList<>(playlists.values());
            // 原子写入：先写同目录临时文件再替换，避免崩溃时留下半截 JSON 导致整个歌单文件被静默丢弃
            JsonFiles.writeAtomic(objectMapper.writerWithDefaultPrettyPrinter(),
                    new File(PLAYLIST_FILE), list);
        } catch (IOException e) {
            System.err.println("❌ Failed to save playlists: " + e.getMessage());
        }
    }

    // 获取所有播放列表
    public List<Playlist> getAllPlaylists() {
        return new ArrayList<>(playlists.values());
    }

    // 按 ID 获取播放列表原始对象（供加载到播放队列等内部逻辑使用）
    public Playlist getPlaylist(String id) {
        return playlists.get(id);
    }

    // 获取播放列表（含歌曲详情）
    public Map<String, Object> getPlaylistWithDetails(String id) {
        Playlist playlist = playlists.get(id);
        if (playlist == null) {
            return null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", playlist.getId());
        result.put("name", playlist.getName());
        result.put("description", playlist.getDescription());
        result.put("createdAt", playlist.getCreatedAt());
        result.put("updatedAt", playlist.getUpdatedAt());
        result.put("size", playlist.getSize());

        // 获取歌曲详情
        List<MusicFile> songs = playlist.getMusicIds().stream()
                .map(scannerService::getMusicById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
        result.put("songs", songs);

        return result;
    }

    // 创建播放列表
    public Playlist createPlaylist(String name, String description) {
        Playlist playlist = new Playlist(name, description);
        playlists.put(playlist.getId(), playlist);
        savePlaylists();
        return playlist;
    }

    // 更新播放列表
    public Playlist updatePlaylist(String id, String name, String description) {
        Playlist playlist = playlists.get(id);
        if (playlist == null) {
            return null;
        }
        if (name != null && !name.isEmpty()) {
            playlist.setName(name);
        }
        if (description != null) {
            playlist.setDescription(description);
        }
        playlist.setUpdatedAt(LocalDateTime.now());
        savePlaylists();
        return playlist;
    }

    // 删除播放列表
    public boolean deletePlaylist(String id) {
        Playlist removed = playlists.remove(id);
        if (removed != null) {
            savePlaylists();
            return true;
        }
        return false;
    }

    /**
     * 添加歌曲到播放列表。
     *
     * @return 仅在本次确实新增了歌曲时返回 {@code true}；
     *         歌单不存在、歌曲不存在、或歌曲已在歌单中，均返回 {@code false}
     */
    public boolean addSongToPlaylist(String playlistId, String musicId) {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            return false;
        }
        // 检查歌曲是否存在
        if (scannerService.getMusicById(musicId).isEmpty()) {
            return false;
        }
        if (!playlist.addMusic(musicId)) {
            // 已在歌单中，无需落盘
            return false;
        }
        savePlaylists();
        return true;
    }

    /**
     * 批量添加歌曲。
     *
     * @return 实际新增的歌曲数量（已存在或音乐库里没有的不会计入）
     */
    public int addSongsToPlaylist(String playlistId, List<String> musicIds) {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            return 0;
        }
        int added = 0;
        for (String musicId : musicIds) {
            if (scannerService.getMusicById(musicId).isPresent() && playlist.addMusic(musicId)) {
                added++;
            }
        }
        if (added > 0) {
            savePlaylists();
        }
        return added;
    }

    /**
     * 从播放列表移除歌曲。
     *
     * @return 仅在歌曲原本存在于歌单中并成功移除时返回 {@code true}
     */
    public boolean removeSongFromPlaylist(String playlistId, String musicId) {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            return false;
        }
        if (!playlist.removeMusic(musicId)) {
            // 歌单中原本就没有这首，无需落盘
            return false;
        }
        savePlaylists();
        return true;
    }

    // 清空播放列表
    public boolean clearPlaylist(String playlistId) {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            return false;
        }
        // getMusicIds() 返回的是只读快照，清空必须走 Playlist 自己的方法
        playlist.clearMusic();
        savePlaylists();
        return true;
    }

    /**
     * 重排序播放列表。
     *
     * <p>完整排列校验下沉到 {@link Playlist#reorderMusic}（长度一致、无重复、每个 ID 都在歌单中），
     * 这里只负责「找到歌单 → 触发重排 → 落盘」。入参不是完整排列时返回 {@code false}，
     * 避免歌曲被静默丢弃或产生重复。</p>
     */
    public boolean reorderPlaylist(String playlistId, List<String> musicIds) {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            return false;
        }
        if (!playlist.reorderMusic(musicIds)) {
            return false;
        }
        savePlaylists();
        return true;
    }
}