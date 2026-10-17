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

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
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
    /**
     * 流式播放音乐（内存优化 + 支持 Range 拖动）。
     *
     * <p>Range 解析兼容三种形式：标准 {@code bytes=start-end}、后缀 {@code bytes=-N}（末尾 N 字节）、
     * 开放区间 {@code bytes=start-}（到末尾）；多段 Range 按 RFC 7233 以
     * {@code multipart/byteranges} 返回。</p>
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

        // 有 Range → 解析后返回指定范围（单段 206，多段 multipart/byteranges）
        try {
            List<long[]> ranges = parseRanges(rangeHeader, fileLength);
            if (ranges == null || ranges.isEmpty()) {
                // 无法解析或含非法段 → 按 RFC 7233 返回 416，并给出文件总长度
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header("Content-Range", "bytes */" + fileLength)
                        .build();
            }

            if (ranges.size() == 1) {
                long[] r = ranges.get(0);
                long start = r[0], end = r[1];
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
            }

            // 多段 → multipart/byteranges
            return buildMultipartRange(file, ranges, fileLength, contentType, musicFile.getFileName());

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 解析 Range 头，返回一组 {@code [start, end]}（含端点，均为闭区间）。
     * 无法解析、含未知单位或任一子段非法时返回 {@code null}。
     */
    private List<long[]> parseRanges(String rangeHeader, long fileLength) {
        if (!rangeHeader.toLowerCase().startsWith("bytes=")) {
            return null; // 仅支持 bytes 单位
        }
        String spec = rangeHeader.substring("bytes=".length()).trim();
        if (spec.isEmpty()) {
            return null;
        }
        List<long[]> result = new ArrayList<>();
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                return null;
            }
            int dash = part.indexOf('-');
            if (dash < 0) {
                return null;
            }
            String startStr = part.substring(0, dash);
            String endStr = part.substring(dash + 1);
            long start, end;
            if (startStr.isEmpty()) {
                // 后缀形式 bytes=-N：取末尾 N 字节
                long suffix;
                try {
                    suffix = Long.parseLong(endStr);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (suffix <= 0) {
                    return null; // 无效后缀（如 -0 或非数字）
                }
                start = Math.max(0, fileLength - suffix);
                end = fileLength - 1;
            } else {
                try {
                    start = Long.parseLong(startStr);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (endStr.isEmpty()) {
                    end = fileLength - 1; // 开放区间 bytes=start-：到末尾
                } else {
                    try {
                        end = Long.parseLong(endStr);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
            // 非法段：越界或反序 → 整个 Range 请求不满足
            if (start < 0 || end < 0 || start > end || start >= fileLength) {
                return null;
            }
            if (end >= fileLength) {
                end = fileLength - 1; // 收敛超界 end（保持 start<=end）
            }
            result.add(new long[]{start, end});
        }
        return result;
    }

    /**
     * 按 RFC 7233 构造多段 Range 的 {@code multipart/byteranges} 响应体。
     */
    private ResponseEntity<byte[]> buildMultipartRange(File file, List<long[]> ranges,
            long fileLength, String contentType, String fileName) throws IOException {
        String boundary = "HELLO_MUSIC_" + System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.UTF_8);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            boolean first = true;
            for (long[] r : ranges) {
                long start = r[0], end = r[1];
                long len = end - start + 1;

                // 段分隔符：首段前无前导 CRLF
                out.write((first ? "--" : "\r\n--").getBytes(StandardCharsets.UTF_8));
                out.write(boundary.getBytes(StandardCharsets.UTF_8));
                out.write(crlf);
                // 段头
                out.write(("Content-Type: " + contentType).getBytes(StandardCharsets.UTF_8));
                out.write(crlf);
                out.write(("Content-Range: bytes " + start + "-" + end + "/" + fileLength)
                        .getBytes(StandardCharsets.UTF_8));
                out.write(crlf);
                out.write(crlf);
                // 段体
                raf.seek(start);
                byte[] buf = new byte[8192];
                long remaining = len;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int read = raf.read(buf, 0, toRead);
                    if (read < 0) break;
                    out.write(buf, 0, read);
                    remaining -= read;
                }
                out.write(crlf);
                first = false;
            }
            // 结束边界
            out.write(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
            out.write(crlf);
        }

        byte[] body = out.toByteArray();
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType("multipart/byteranges; boundary=" + boundary))
                .contentLength(body.length)
                .header("Accept-Ranges", "bytes")
                .header("X-File-Name", fileName)
                .body(body);
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

    /**
     * 局部更新配置：只提交要改的字段即可，未提交的字段保持原值（见 {@code ConfigManager.mergeInto}）。
     *
     * <p>取值非法（端口越界、扩展名列表为空……）时由 {@code @Valid} 拦下，
     * 统一返回 400 及逐字段的原因，不会写坏配置文件。
     */
    @PutMapping("/config")
    public ResponseEntity<ConfigManager.Config> updateConfig(
            @Valid @RequestBody ConfigManager.Config config) {
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