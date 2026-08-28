package com;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class MusicController {
    private final MusicScannerService scannerService;
    private final BroadcastService broadcastService;
    private final ConfigManager configManager;

    @Autowired
    public MusicController(MusicScannerService scannerService,
                           BroadcastService broadcastService,
                           ConfigManager configManager) {
        this.scannerService = scannerService;
        this.broadcastService = broadcastService;
        this.configManager = configManager;
        updateBroadcastStats();
    }

    private void updateBroadcastStats() {
        broadcastService.updateLibrarySize(scannerService.getLibrarySize());
    }

    @GetMapping("/music")
    public ResponseEntity<List<MusicFile>> getAllMusic() {
        return ResponseEntity.ok(scannerService.getAllMusic());
    }

    @GetMapping("/music/{id}")
    public ResponseEntity<MusicFile> getMusicById(@PathVariable String id) {
        MusicFile musicFile = scannerService.getMusicById(id).orElse(null);
        if (musicFile == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(musicFile);
    }

    @GetMapping("/music/search")
    public ResponseEntity<List<MusicFile>> searchMusic(@RequestParam String q) {
        if (q == null || q.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(scannerService.searchMusic(q.trim()));
    }

    @GetMapping("/music/artist/{artist}")
    public ResponseEntity<List<MusicFile>> getMusicByArtist(@PathVariable String artist) {
        return ResponseEntity.ok(scannerService.getMusicByArtist(artist));
    }

    @GetMapping("/artists")
    public ResponseEntity<Map<String, List<MusicFile>>> getArtists() {
        return ResponseEntity.ok(scannerService.getArtists());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = Map.of(
                "totalFiles", scannerService.getLibrarySize(),
                "scanning", scannerService.isScanning(),
                "lastScan", scannerService.getLastScanTime(),
                "config", configManager.getConfig()
        );
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> triggerScan() {
        new Thread(() -> {
            scannerService.scanLibrary();
            updateBroadcastStats();
        }).start();
        return ResponseEntity.ok(Map.of("status", "Scan started"));
    }

    @GetMapping(value = "/stream/{id}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> streamMusic(@PathVariable String id) {
        MusicFile musicFile = scannerService.getMusicById(id).orElse(null);
        if (musicFile == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            File file = Paths.get(musicFile.getFilePath()).toFile();
            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }

            byte[] data = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.read(data);
            }

            String contentType = getContentType(musicFile.getExtension());

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.length()))
                    .header("X-File-Name", musicFile.getFileName())
                    .body(data);

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/broadcast")
    public ResponseEntity<Map<String, String>> broadcastNow() {
        broadcastService.sendBroadcastNow();
        return ResponseEntity.ok(Map.of("status", "Broadcast sent"));
    }

    @GetMapping("/config")
    public ResponseEntity<ConfigManager.Config> getConfig() {
        return ResponseEntity.ok(configManager.getConfig());
    }

    @PutMapping("/config")
    public ResponseEntity<ConfigManager.Config> updateConfig(@RequestBody ConfigManager.Config config) {
        configManager.setConfig(config);
        scannerService.scanLibrary();
        updateBroadcastStats();
        return ResponseEntity.ok(configManager.getConfig());
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getInfo() {
        return ResponseEntity.ok(Map.of(
                "name", "HelloMusic",
                "version", "1.0.0",
                "status", "running",
                "librarySize", scannerService.getLibrarySize()
        ));
    }

    private String getContentType(String extension) {
        return switch (extension.toLowerCase()) {
            case "mp3" -> "audio/mpeg";
            case "mkv" -> "video/x-matroska";
            case "flac" -> "audio/flac";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4a-latm";
            case "ogg" -> "audio/ogg";
            default -> "application/octet-stream";
        };
    }
}