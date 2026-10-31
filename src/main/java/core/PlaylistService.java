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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;

public class PlaylistService {
    private static final Logger log = LoggerFactory.getLogger(PlaylistService.class);
    private final Map<String, Playlist> playlists = new ConcurrentHashMap<>();
    private final MusicScannerService scannerService;
    private static final String PLAYLIST_FILE = "playlists.json";
    private final ObjectMapper objectMapper;

    /**
     * 传入统一的 {@link ObjectMapper}（见 {@link Json#newMapper()}），而不是自行 {@code new ObjectMapper()}。
     *
     * <p>Playlist 含 {@code LocalDateTime} 字段，而裸 ObjectMapper 未注册 JavaTimeModule，
     * Jackson 2.12+ 会直接抛 {@code InvalidDefinitionException}（
     * "Java 8 date/time type java.time.LocalDateTime not supported by default"）。
     * 该异常属于 IOException，会被下面的 catch 静默吞掉，导致 playlists.json 既写不进也读不出。
     * 经 {@link Json#newMapper()} 构造的 mapper 已注册 jsr310 模块，可正确序列化。</p>
     */
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
                log.info("已从 " + PLAYLIST_FILE + " 加载 " + playlists.size() + " 个歌单");
            } catch (IOException e) {
                log.warn("加载歌单失败: " + e.getMessage());
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
            log.warn("保存歌单失败: " + e.getMessage());
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

        // 取一次快照，size 与 songs 同源：若分开调 getSize() 与 getMusicIds()，
        // 并发加歌/删歌时两次调用可能看到不同的歌单状态，导致 size 与 songs.size() 不一致。
        List<String> musicIds = playlist.getMusicIds();
        result.put("size", musicIds.size());

        // 获取歌曲详情
        List<MusicFile> songs = musicIds.stream()
                .map(scannerService::getMusicById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
        result.put("songs", songs);

        return result;
    }

    // 创建播放列表
    public synchronized Playlist createPlaylist(String name, String description) {
        Playlist playlist = new Playlist(name, description);
        playlists.put(playlist.getId(), playlist);
        savePlaylists();
        return playlist;
    }

    // 更新播放列表
    public synchronized Playlist updatePlaylist(String id, String name, String description) {
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
    public synchronized boolean deletePlaylist(String id) {
        Playlist removed = playlists.remove(id);
        if (removed != null) {
            savePlaylists();
            return true;
        }
        return false;
    }

    /**
     * 单曲添加到歌单的结果，供调用方区分「歌单不存在 / 歌曲不存在 / 已在歌单中 / 成功新增」，
     * 让 HTTP 层能返回准确的 404 / 409，而不是笼统的 400。
     */
    public enum AddResult {
        /** 本次确实新增。 */
        ADDED,
        /** 歌单不存在。 */
        PLAYLIST_NOT_FOUND,
        /** 歌曲不在曲库中。 */
        SONG_NOT_FOUND,
        /** 歌曲已在歌单中。 */
        ALREADY_PRESENT
    }

    /**
     * 添加歌曲到播放列表。
     *
     * @return 区分「成功新增」与三种不同失败原因（见 {@link AddResult}）
     */
    public synchronized AddResult addSongToPlaylist(String playlistId, String musicId) {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            return AddResult.PLAYLIST_NOT_FOUND;
        }
        // 检查歌曲是否存在
        if (scannerService.getMusicById(musicId).isEmpty()) {
            return AddResult.SONG_NOT_FOUND;
        }
        if (!playlist.addMusic(musicId)) {
            // 已在歌单中，无需落盘
            return AddResult.ALREADY_PRESENT;
        }
        savePlaylists();
        return AddResult.ADDED;
    }

    /**
     * 批量添加歌曲。
     *
     * @return 实际新增的歌曲数量（已存在或音乐库里没有的不会计入）
     */
    public synchronized int addSongsToPlaylist(String playlistId, List<String> musicIds) {
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
    public synchronized boolean removeSongFromPlaylist(String playlistId, String musicId) {
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
    public synchronized boolean clearPlaylist(String playlistId) {
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
    public synchronized boolean reorderPlaylist(String playlistId, List<String> musicIds) {
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