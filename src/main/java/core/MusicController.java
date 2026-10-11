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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final TaskExecutor taskExecutor;

    @Autowired
    public MusicController(MusicScannerService scannerService,
                          BroadcastService broadcastService,
                          ConfigManager configManager,
                          TaskExecutor taskExecutor) {
        this.scannerService = scannerService;
        this.broadcastService = broadcastService;
        this.configManager = configManager;
        this.taskExecutor = taskExecutor;
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
        // 交给 Spring 托管的线程池执行，避免裸建线程且便于统一管理
        // 广播中的库大小由 BroadcastService 动态读取，无需在此回写
        taskExecutor.execute(scannerService::scanLibrary);
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

            // 兼容部分播放器发送的超界 Range：把 end 收敛到文件末尾，而不是直接返回 416
            if (end >= fileLength) {
                end = fileLength - 1;
            }

            if (start < 0 || start >= fileLength || start > end) {
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

        } catch (NumberFormatException e) {
            // 无法解析的 Range 头，按 RFC 7233 返回 416
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header("Content-Range", "bytes */" + fileLength)
                    .build();
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
        // 配置变更后需要重新扫描，但扫描可能耗时数分钟。
        // 放到线程池异步执行，避免 HTTP 请求被扫描阻塞直至超时。
        taskExecutor.execute(scannerService::scanLibrary);
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