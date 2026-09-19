package com;

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

    @Autowired
    public PlaylistService(MusicScannerService scannerService) {
        this.scannerService = scannerService;
        this.objectMapper = new ObjectMapper();
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
    private void savePlaylists() {
        try {
            List<Playlist> list = new ArrayList<>(playlists.values());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(PLAYLIST_FILE), list);
        } catch (IOException e) {
            System.err.println("❌ Failed to save playlists: " + e.getMessage());
        }
    }

    // 获取所有播放列表
    public List<Playlist> getAllPlaylists() {
        return new ArrayList<>(playlists.values());
    }

    // 获取单个播放列表（含完整歌曲信息）
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

    // 添加歌曲到播放列表
    public boolean addSongToPlaylist(String playlistId, String musicId) {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            return false;
        }
        // 检查歌曲是否存在
        if (scannerService.getMusicById(musicId).isEmpty()) {
            return false;
        }
        playlist.addMusic(musicId);
        savePlaylists();
        return true;
    }

    // 批量添加歌曲
    public int addSongsToPlaylist(String playlistId, List<String> musicIds) {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            return 0;
        }
        int added = 0;
        for (String musicId : musicIds) {
            if (scannerService.getMusicById(musicId).isPresent()) {
                playlist.addMusic(musicId);
                added++;
            }
        }
        if (added > 0) {
            savePlaylists();
        }
        return added;
    }

    // 从播放列表移除歌曲
    public boolean removeSongFromPlaylist(String playlistId, String musicId) {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            return false;
        }
        playlist.removeMusic(musicId);
        savePlaylists();
        return true;
    }

    // 清空播放列表
    public boolean clearPlaylist(String playlistId) {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            return false;
        }
        playlist.getMusicIds().clear();
        playlist.setUpdatedAt(LocalDateTime.now());
        savePlaylists();
        return true;
    }

    // 重排序播放列表
    public boolean reorderPlaylist(String playlistId, List<String> musicIds) {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) {
            return false;
        }
        // 验证所有 ID 都存在
        for (String id : musicIds) {
            if (!playlist.getMusicIds().contains(id)) {
                return false;
            }
        }
        playlist.setMusicIds(new ArrayList<>(musicIds));
        playlist.setUpdatedAt(LocalDateTime.now());
        savePlaylists();
        return true;
    }
}