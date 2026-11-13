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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 统一 HTTP 网关：对外唯一的一扇 HTTP 门（JDK 内置 {@code com.sun.net.httpserver}，零额外依赖）。
 *
 * <p>它同时承载两类内容，兑现「对外都是 HTTP」的约定：</p>
 * <ul>
 *   <li><b>控制面</b>：{@code POST /control} —— 把 JSON 请求体
 *       {@code {"cmd": "...", ...参数}} 转发给 {@link CommandDispatcher}，其响应中的
 *       {@code status} 字段被映射为真实的 HTTP 状态码。这样本机客户端走零拷贝的 Unix Socket
 *       （见 {@link UnixSocketServer}），局域网 / 远程客户端则通过 HTTP 走同一条命令分发链路；</li>
 *   <li><b>数据面（媒体）</b>：{@code GET /stream/{id}}（音频流，支持 {@code Range} 拖动）、
 *       {@code GET /cover/{id}}（封面图）、{@code GET /lyrics/{id}}（歌词文本），
 *       供浏览器 {@code <audio>} / {@code <img>} 与局域网播放器直接拉取字节。</li>
 * </ul>
 *
 * <p>控制命令协议与 Unix Socket 侧完全一致：请求体是一个 JSON 对象（含 {@code cmd} 字段），
 * 响应体是 {@code {"ok": true, "data": ...}} 或 {@code {"ok": false, "error": ..., "status": <code>}}。
 * 唯一差别是传输层（HTTP over TCP vs Unix Socket）与错误表达方式（HTTP 状态码 vs JSON {@code status} 字段）。</p>
 */
public class HttpGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpGateway.class);

    /** 控制面请求体大小上限（256KB）。命令都是短 JSON，256KB 已远超实际需求；超限返回 413，防止恶意大 body 撑爆内存。 */
    static final int MAX_CONTROL_BODY_BYTES = 256 * 1024;

    private final int port;
    private final MusicScannerService scannerService;
    private final PreloadService preloadService;
    private final CommandDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private HttpServer server;
    /** 处理 HTTP 交换的有界线程池；与网关同生命周期，{@link #stop()} 时关闭（线程均为守护线程）。 */
    private final ExecutorService executor =
            ThreadPools.bounded("hellomusic-http", 16, 200,
                    new ThreadPoolExecutor.AbortPolicy());

    public HttpGateway(int port,
                       MusicScannerService scannerService,
                       PreloadService preloadService,
                       CommandDispatcher dispatcher,
                       ObjectMapper objectMapper) {
        this.port = port;
        this.scannerService = scannerService;
        this.preloadService = preloadService;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /** 启动 HTTP 网关（控制面 + 数据面）。调用方应放在独立线程，或做好阻塞准备。 */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/control", this::handleControl);
        server.createContext("/stream", this::handleStream);
        server.createContext("/cover", this::handleCover);
        server.createContext("/lyrics", this::handleLyrics);
        server.setExecutor(executor);
        server.start();
        log.info("HTTP 网关已监听: http://localhost:" + getPort()
                + "（控制 POST /control，媒体 /stream /cover /lyrics）");
    }

    /** 停止 HTTP 网关，并关闭其处理线程池。 */
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        executor.shutdown();
    }

    /** 实际绑定的端口（端口传 0 时由系统分配，供测试读取）。 */
    public int getPort() {
        return server == null ? port : server.getAddress().getPort();
    }

    // ===== 控制面 =====

    private void handleControl(HttpExchange exchange) throws IOException {
        // 跨域：浏览器跨源调用控制面时会先发 OPTIONS 预检
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("ok", false, "error", "仅支持 POST", "status", 405));
            return;
        }

        JsonNode request;
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_CONTROL_BODY_BYTES + 1);
            if (body.length > MAX_CONTROL_BODY_BYTES) {
                sendJson(exchange, 413, Map.of("ok", false, "error", "请求体过大", "status", 413));
                return;
            }
            request = objectMapper.readTree(body);
        } catch (IOException e) {
            sendJson(exchange, 400, Map.of("ok", false, "error", "请求不是合法 JSON", "status", 400));
            return;
        }
        if (request == null || !request.isObject()) {
            sendJson(exchange, 400, Map.of("ok", false, "error", "请求必须是 JSON 对象", "status", 400));
            return;
        }

        Map<String, Object> response = dispatcher.dispatch(request, remoteAddrOf(exchange));
        sendJson(exchange, httpStatusOf(response), response);
    }

    /** 从命令响应中取 {@code status} 字段映射为 HTTP 状态码；成功响应无该字段，默认 200。 */
    private static int httpStatusOf(Map<String, Object> response) {
        Object status = response.get("status");
        if (status instanceof Number number) {
            int code = number.intValue();
            return code >= 100 && code <= 599 ? code : 500;
        }
        return 200;
    }

    private void sendJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ===== 音频流 =====

    private void handleStream(HttpExchange exchange) throws IOException {
        String id = pathSegment(exchange, "/stream/");
        MusicFile musicFile = id == null ? null : scannerService.getMusicById(id).orElse(null);
        if (musicFile == null) {
            sendEmpty(exchange, 404);
            return;
        }
        File file = Paths.get(musicFile.getFilePath()).toFile();
        if (!file.exists()) {
            sendEmpty(exchange, 404);
            return;
        }

        long fileLength = file.length();
        String contentType = getContentType(musicFile.getExtension());
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");

        if (rangeHeader == null) {
            // 无 Range → 流式返回完整文件
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("X-File-Name", musicFile.getFileName());
            exchange.sendResponseHeaders(200, fileLength);
            try (InputStream in = new java.io.FileInputStream(file);
                 OutputStream out = exchange.getResponseBody()) {
                in.transferTo(out);
            }
            return;
        }

        // 有 Range → 解析后返回指定范围
        List<long[]> ranges = parseRanges(rangeHeader, fileLength);
        if (ranges == null || ranges.isEmpty()) {
            exchange.getResponseHeaders().set("Content-Range", "bytes */" + fileLength);
            sendEmpty(exchange, 416);
            return;
        }

        // 多段 Range 只返回第一段，其余段忽略。
        // 目标客户端（浏览器 <audio>）只会发单段 Range，多段请求实际不会出现；但为遵守
        // RFC 7233，这里不把多段折叠成覆盖全部请求段的连续区间——那会返回客户端并未请求的
        // 中间字节，并让 Content-Range 谎报一个连续区间（协议违规，客户端按多段语义解析会出错）。
        // 真正支持多段需 multipart/byteranges 边界编码，因目标客户端不发多段，暂不实现，
        // 按 RFC 7233 允许的降级策略退化为「只返回第一段」，并在下方 Content-Range 里如实体现。
        long start = ranges.get(0)[0];
        long end = ranges.get(0)[1];
        long contentLength = end - start + 1;

        // 预缓冲加速：从 0 开始、且落在已预载首段内的请求，直接从内存返回
        byte[] cached = preloadService.getPreloaded(id);
        if (start == 0 && cached != null && end < cached.length) {
            byte[] chunk = Arrays.copyOfRange(cached, (int) start, (int) (end + 1));
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
            exchange.getResponseHeaders().set("X-File-Name", musicFile.getFileName());
            exchange.getResponseHeaders().set("X-Preloaded", "1");
            exchange.sendResponseHeaders(206, chunk.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(chunk);
            }
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
        exchange.getResponseHeaders().set("X-File-Name", musicFile.getFileName());
        exchange.sendResponseHeaders(206, contentLength);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             OutputStream out = exchange.getResponseBody()) {
            raf.seek(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = contentLength;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, toRead);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    // ===== 封面 =====

    private void handleCover(HttpExchange exchange) throws IOException {
        String id = pathSegment(exchange, "/cover/");
        MusicFile musicFile = id == null ? null : scannerService.getMusicById(id).orElse(null);
        if (musicFile == null) {
            sendEmpty(exchange, 404);
            return;
        }
        MetadataExtractor.Cover cover = MetadataExtractor.extractCover(
                Paths.get(musicFile.getFilePath()).toFile());
        if (cover == null) {
            sendEmpty(exchange, 404);
            return;
        }
        String mime = cover.mime();
        if (mime == null || mime.isEmpty() || !mime.contains("/")) {
            mime = "application/octet-stream";
        }
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.sendResponseHeaders(200, cover.data().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(cover.data());
        }
    }

    // ===== 歌词 =====

    private void handleLyrics(HttpExchange exchange) throws IOException {
        String id = pathSegment(exchange, "/lyrics/");
        MusicFile musicFile = id == null ? null : scannerService.getMusicById(id).orElse(null);
        if (musicFile == null) {
            sendEmpty(exchange, 404);
            return;
        }
        String lyrics = MetadataExtractor.extractLyrics(Paths.get(musicFile.getFilePath()).toFile());
        if (lyrics == null) {
            sendEmpty(exchange, 404);
            return;
        }
        byte[] body = lyrics.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // ===== 工具 =====

    /** 从 HTTP 连接提取真实对端 IP，供会话 {@code connect} 记录 remoteAddr（观测值，非客户端自报）。 */
    private static String remoteAddrOf(HttpExchange exchange) {
        if (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null) {
            return "unknown";
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    /** 从请求路径中提取 {@code /prefix/{id}} 的 {@code id} 段。 */
    private static String pathSegment(HttpExchange exchange, String prefix) {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix)) {
            return null;
        }
        String rest = path.substring(prefix.length());
        if (rest.isEmpty()) {
            return null;
        }
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    /**
     * 解析 Range 头，返回一组 {@code [start, end]}（含端点，闭区间）。
     * 无法解析、含未知单位或任一子段非法时返回 {@code null}。
     */
    static List<long[]> parseRanges(String rangeHeader, long fileLength) {
        if (rangeHeader == null || !rangeHeader.toLowerCase().startsWith("bytes=")) {
            return null;
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
                long suffix;
                try {
                    suffix = Long.parseLong(endStr);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (suffix <= 0) {
                    return null;
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
                    end = fileLength - 1;
                } else {
                    try {
                        end = Long.parseLong(endStr);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
            if (start < 0 || end < 0 || start > end || start >= fileLength) {
                return null;
            }
            if (end >= fileLength) {
                end = fileLength - 1;
            }
            result.add(new long[]{start, end});
        }
        return result;
    }

    private static String getContentType(String extension) {
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
