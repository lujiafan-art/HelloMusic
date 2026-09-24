package core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
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

    /**
     * 流式播放音乐（内存优化 + 支持 Range 拖动）
     */
    @GetMapping("/stream/{id}")
    public ResponseEntity<?> streamMusic(
            @PathVariable String id,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {

        MusicFile musicFile = scannerService.getMusicById(id).orElse(null);
        if (musicFile == null) {
            return ResponseEntity.notFound().build();
        }

        File file = Paths.get(musicFile.getFilePath()).toFile();
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        long fileLength = file.length();
        String contentType = getContentType(musicFile.getExtension());

        // 无 Range → 返回完整文件（流式）
        if (rangeHeader == null) {
            try {
                InputStreamResource resource = new InputStreamResource(new FileInputStream(file));
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .contentLength(fileLength)
                        .header("Accept-Ranges", "bytes")
                        .header("X-File-Name", musicFile.getFileName())
                        .body(resource);
            } catch (IOException e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }

        // 有 Range → 返回指定范围
        try {
            String range = rangeHeader.replace("bytes=", "").trim();
            String[] parts = range.split("-");
            long start = Long.parseLong(parts[0]);
            long end = parts.length > 1 && !parts[1].isEmpty()
                    ? Long.parseLong(parts[1])
                    : fileLength - 1;

            if (start >= fileLength || end >= fileLength || start > end) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header("Content-Range", "bytes */" + fileLength)
                        .build();
            }

            long contentLength = end - start + 1;
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.seek(start);

            InputStream limitedStream = new InputStream() {
                private long remaining = contentLength;

                @Override
                public int read() throws IOException {
                    if (remaining <= 0) return -1;
                    int b = raf.read();
                    if (b != -1) remaining--;
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    int toRead = (int) Math.min(len, remaining);
                    int read = raf.read(b, off, toRead);
                    if (read > 0) remaining -= read;
                    return read;
                }

                @Override
                public void close() throws IOException {
                    raf.close();
                }
            };

            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(contentLength)
                    .header("Accept-Ranges", "bytes")
                    .header("Content-Range", "bytes " + start + "-" + end + "/" + fileLength)
                    .header("X-File-Name", musicFile.getFileName())
                    .body(new InputStreamResource(limitedStream));

        } catch (Exception e) {
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