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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 控制面服务器：在 Unix domain socket 上监听，使用「JSON 行协议」与客户端通信。
 *
 * <p>每个连接一条线程（交给线程池管理）：逐行读取 JSON 请求，交给 {@link CommandDispatcher}
 * 分发，再把 JSON 响应写回一行。相比 HTTP，它零 TCP/IP 协议栈、不占端口、无端口冲突，适合
 * 本机高并发进程间控制。</p>
 *
 * <p>协议约定：</p>
 * <ul>
 *   <li>请求：一行 JSON 对象（以 {@code \n} 结尾），如 {@code {"cmd": "play", "sessionId": "0"}}；</li>
 *   <li>响应：一行 JSON 对象，如 {@code {"ok": true, "data": {...}}}；</li>
 *   <li>连接双方任一方关闭 socket 即结束会话；服务端不主动断开（除非客户端断开或连接出错）。</li>
 * </ul>
 */
public class UnixSocketServer {

    private static final Logger log = LoggerFactory.getLogger(UnixSocketServer.class);

    private final Path socketPath;
    private final CommandDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService connectionPool =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "hellomusic-socket-conn");
                t.setDaemon(true);
                return t;
            });

    private volatile ServerSocketChannel serverChannel;

    public UnixSocketServer(Path socketPath, CommandDispatcher dispatcher, ObjectMapper objectMapper) {
        this.socketPath = socketPath;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动并阻塞在当前线程：准备 socket 路径 → 绑定 → 循环 accept 分发连接。
     *
     * <p>应放在独立线程里调用（见 {@code HelloMusicApplication}）。</p>
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        prepareSocketPath();
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socketPath));
            serverChannel = server;
            log.info("控制面已监听 Unix Socket: " + socketPath);
            while (running.get()) {
                SocketChannel channel;
                try {
                    channel = server.accept();
                } catch (IOException e) {
                    if (!running.get()) {
                        break; // 正常关闭
                    }
                    log.warn("accept 失败: " + e.getMessage());
                    continue;
                }
                connectionPool.execute(() -> handle(channel));
            }
        } catch (IOException e) {
            log.error("控制面启动失败: " + e.getMessage(), e);
        } finally {
            cleanupSocketPath();
            connectionPool.shutdown();
        }
    }

    /** 请求停止：关闭监听 channel，让 accept 循环退出。 */
    public void stop() {
        running.set(false);
        ServerSocketChannel server = serverChannel;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 单个连接的处理：读一行 JSON → 分发 → 写一行 JSON 响应，直到连接关闭。 */
    private void handle(SocketChannel channel) {
        try (channel) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    Channels.newInputStream(channel), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    Channels.newOutputStream(channel), StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String response = dispatchLine(line);
                writer.write(response);
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException e) {
            // 客户端断开或读写失败：结束该连接即可，不影响服务
            log.debug("连接结束: " + e.getMessage());
        }
    }

    /** 解析一行请求并返回一行响应（抽成独立方法便于测试）。 */
    String dispatchLine(String line) {
        JsonNode request;
        try {
            request = objectMapper.readTree(line);
        } catch (IOException e) {
            return objectMapper.valueToTree(
                    Map.of("ok", false, "error", "请求不是合法 JSON", "status", 400)).toString();
        }
        if (request == null || !request.isObject()) {
            return objectMapper.valueToTree(
                    Map.of("ok", false, "error", "请求必须是 JSON 对象", "status", 400)).toString();
        }
        try {
            return objectMapper.writeValueAsString(dispatcher.dispatch(request));
        } catch (IOException e) {
            return objectMapper.valueToTree(
                    Map.of("ok", false, "error", "响应序列化失败", "status", 500)).toString();
        }
    }

    /** 确保 socket 父目录存在，并清理上次异常退出残留的 socket 文件。 */
    private void prepareSocketPath() {
        try {
            Path parent = socketPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(socketPath);
        } catch (IOException e) {
            throw new IllegalStateException("无法准备 Unix Socket 路径: " + socketPath, e);
        }
    }

    /** 关闭时清理 socket 文件，避免下次启动 bind 冲突。 */
    private void cleanupSocketPath() {
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
        }
    }
}
