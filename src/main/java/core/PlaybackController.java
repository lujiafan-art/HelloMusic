package com;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/playback")
@CrossOrigin(origins = "*")
public class PlaybackController {

    private final PlaybackService playbackService;
    private final MusicScannerService scannerService;

    @Autowired
    public PlaybackController(PlaybackService playbackService, MusicScannerService scannerService) {
        this.playbackService = playbackService;
        this.scannerService = scannerService;
    }

    // 获取当前播放模式
    @GetMapping("/mode")
    public ResponseEntity<Map<String, Object>> getMode() {
        PlayMode mode = playbackService.getCurrentMode();
        return ResponseEntity.ok(Map.of(
                "mode", mode.name(),
                "displayName", mode.getDisplayName()
        ));
    }

    // 切换播放模式
    @PostMapping("/mode/switch")
    public ResponseEntity<Map<String, Object>> switchMode() {
        PlayMode mode = playbackService.switchMode();
        return ResponseEntity.ok(Map.of(
                "mode", mode.name(),
                "displayName", mode.getDisplayName()
        ));
    }

    // 设置播放模式
    @PostMapping("/mode")
    public ResponseEntity<Map<String, Object>> setMode(@RequestParam String mode) {
        PlayMode playMode;
        try {
            playMode = PlayMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的播放模式"));
        }
        PlayMode result = playbackService.setMode(playMode);
        return ResponseEntity.ok(Map.of(
                "mode", result.name(),
                "displayName", result.getDisplayName()
        ));
    }

    // 获取当前播放歌曲
    @GetMapping("/current")
    public ResponseEntity<?> getCurrentSong() {
        MusicFile current = playbackService.getCurrentSong();
        if (current == null) {
            return ResponseEntity.ok(Map.of("status", "empty"));
        }
        return ResponseEntity.ok(current);
    }

    // 下一首
    @PostMapping("/next")
    public ResponseEntity<?> next() {
        MusicFile next = playbackService.next();
        if (next == null) {
            return ResponseEntity.ok(Map.of("status", "end"));
        }
        return ResponseEntity.ok(next);
    }

    // 上一首
    @PostMapping("/previous")
    public ResponseEntity<?> previous() {
        MusicFile prev = playbackService.previous();
        if (prev == null) {
            return ResponseEntity.ok(Map.of("status", "begin"));
        }
        return ResponseEntity.ok(prev);
    }

    // 加载全部音乐到播放队列
    @PostMapping("/load/all")
    public ResponseEntity<Map<String, Object>> loadAll() {
        playbackService.loadAllMusic();
        return ResponseEntity.ok(Map.of("status", "loaded", "total", playbackService.getQueue().size()));
    }

    // 加载播放列表（根据歌曲ID列表）
    @PostMapping("/load")
    public ResponseEntity<Map<String, Object>> loadPlaylist(@RequestBody List<String> musicIds) {
        if (musicIds == null || musicIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "音乐列表不能为空"));
        }
        playbackService.loadPlaylist(musicIds);
        return ResponseEntity.ok(Map.of("status", "loaded", "total", playbackService.getQueue().size()));
    }

    // 获取播放队列信息
    @GetMapping("/queue")
    public ResponseEntity<Map<String, Object>> getQueueInfo() {
        return ResponseEntity.ok(playbackService.getQueueInfo());
    }

    // 清空播放队列
    @DeleteMapping("/queue")
    public ResponseEntity<Map<String, String>> clearQueue() {
        playbackService.clearQueue();
        return ResponseEntity.ok(Map.of("status", "cleared"));
    }

    // 添加歌曲到播放队列
    @PostMapping("/queue/add")
    public ResponseEntity<Map<String, String>> addToQueue(@RequestParam String musicId) {
        if (scannerService.getMusicById(musicId).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "歌曲不存在"));
        }
        playbackService.addToQueue(musicId);
        return ResponseEntity.ok(Map.of("status", "added"));
    }

    // 批量添加到播放队列
    @PostMapping("/queue/add/batch")
    public ResponseEntity<Map<String, Object>> addBatchToQueue(@RequestBody List<String> musicIds) {
        if (musicIds == null || musicIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "音乐列表不能为空"));
        }
        int added = 0;
        for (String id : musicIds) {
            if (scannerService.getMusicById(id).isPresent()) {
                playbackService.addToQueue(id);
                added++;
            }
        }
        return ResponseEntity.ok(Map.of("status", "added", "count", added));
    }
}