package com;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/playlists")
@CrossOrigin(origins = "*")
public class PlaylistController {

    private final PlaylistService playlistService;

    @Autowired
    public PlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    // 1. 获取所有播放列表
    @GetMapping
    public ResponseEntity<List<Playlist>> getAllPlaylists() {
        return ResponseEntity.ok(playlistService.getAllPlaylists());
    }

    // 2. 获取单个播放列表（含歌曲详情）
    @GetMapping("/{id}")
    public ResponseEntity<?> getPlaylist(@PathVariable String id) {
        Map<String, Object> playlist = playlistService.getPlaylistWithDetails(id);
        if (playlist == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(playlist);
    }

    // 3. 创建播放列表
    @PostMapping
    public ResponseEntity<Playlist> createPlaylist(
            @RequestParam String name,
            @RequestParam(required = false) String description) {
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        Playlist playlist = playlistService.createPlaylist(name.trim(), description);
        return ResponseEntity.ok(playlist);
    }

    // 4. 更新播放列表
    @PutMapping("/{id}")
    public ResponseEntity<Playlist> updatePlaylist(
            @PathVariable String id,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description) {
        Playlist playlist = playlistService.updatePlaylist(id, name, description);
        if (playlist == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(playlist);
    }

    // 5. 删除播放列表
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePlaylist(@PathVariable String id) {
        boolean deleted = playlistService.deletePlaylist(id);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("status", "Deleted"));
    }

    // 6. 添加歌曲到播放列表
    @PostMapping("/{id}/songs")
    public ResponseEntity<?> addSongToPlaylist(
            @PathVariable String id,
            @RequestParam String musicId) {
        boolean added = playlistService.addSongToPlaylist(id, musicId);
        if (!added) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(Map.of("status", "Added"));
    }

    // 7. 批量添加歌曲
    @PostMapping("/{id}/songs/batch")
    public ResponseEntity<?> addSongsToPlaylist(
            @PathVariable String id,
            @RequestBody List<String> musicIds) {
        if (musicIds == null || musicIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "musicIds is required"));
        }
        int added = playlistService.addSongsToPlaylist(id, musicIds);
        return ResponseEntity.ok(Map.of("added", added));
    }

    // 8. 从播放列表移除歌曲
    @DeleteMapping("/{id}/songs/{musicId}")
    public ResponseEntity<?> removeSongFromPlaylist(
            @PathVariable String id,
            @PathVariable String musicId) {
        boolean removed = playlistService.removeSongFromPlaylist(id, musicId);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("status", "Removed"));
    }

    // 9. 清空播放列表
    @DeleteMapping("/{id}/clear")
    public ResponseEntity<?> clearPlaylist(@PathVariable String id) {
        boolean cleared = playlistService.clearPlaylist(id);
        if (!cleared) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("status", "Cleared"));
    }

    // 10. 重排序播放列表
    @PutMapping("/{id}/reorder")
    public ResponseEntity<?> reorderPlaylist(
            @PathVariable String id,
            @RequestBody List<String> musicIds) {
        boolean reordered = playlistService.reorderPlaylist(id, musicIds);
        if (!reordered) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(Map.of("status", "Reordered"));
    }
}