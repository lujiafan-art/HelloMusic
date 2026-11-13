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

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Unix Socket 控制面客户端：让 HTTP 网关把控制命令转发给本机 {@link UnixSocketServer}，
 * 使 HTTP 成为 Unix Socket 的「外壳」，而不是另一条独立的控制实现。
 *
 * <p>它按 {@link UnixSocketServer} 的 JSON 行协议工作：每次 {@link #dispatch(JsonNode)}
 * 建立一条到本机 socket 的连接、写入一行 JSON 请求、读回一行 JSON 响应后关闭。
 * 控制面是低频短请求，每次新建连接的开销可忽略，省去连接池的复用与竞态复杂度。</p>
 */
public class UnixSocketClient {

    private final Path socketPath;
    private final ObjectMapper objectMapper;

    public UnixSocketClient(Path socketPath, ObjectMapper objectMapper) {
        this.socketPath = socketPath;
        this.objectMapper = objectMapper;
    }

    /**
     * 把一条命令请求转发给本机 Unix Socket 控制面，返回其 JSON 响应。
     *
     * @param request 命令请求 JSON（含 {@code cmd} 字段）
     * @return 控制面返回的响应 JSON（恒非 {@code null}）
     * @throws IOException 连接失败、读写失败，或控制面返回了空响应
     */
    public JsonNode dispatch(JsonNode request) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));

            byte[] line = (objectMapper.writeValueAsString(request) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            ByteBuffer out = ByteBuffer.wrap(line);
            while (out.hasRemaining()) {
                channel.write(out);
            }

            String response = readLine(channel);
            if (response == null || response.isBlank()) {
                throw new IOException("控制面返回了空响应");
            }
            return objectMapper.readTree(response);
        }
    }

    /** 逐字节读取一行响应（以 {@code \n} 结尾，兼容 {@code \r\n}）；流结束时返回 {@code null}。 */
    private String readLine(SocketChannel channel) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        ByteBuffer buf = ByteBuffer.allocate(1024);
        while (true) {
            buf.clear();
            int n = channel.read(buf);
            if (n < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            buf.flip();
            while (buf.hasRemaining()) {
                byte b = buf.get();
                if (b == '\n' || b == '\r') {
                    return sb.toString();
                }
                sb.append((char) b);
            }
        }
    }
}
