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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP 网关测试：验证 HTTP 是 Unix Socket 控制面的「外壳」，而不是独立的控制实现。
 *
 * <p>控制面测试起一个真实的 {@link UnixSocketServer}（内嵌 {@code mock(CommandDispatcher)}），
 * 再用指向它的 {@link HttpGateway} 发真实 HTTP 请求——请求必须真正穿过 Unix Socket 链路，
 * 转发到服务端、再透传回来。鉴权、请求体上限、数据面 Range 则在网关入口与媒体层独立验证。</p>
 */
class HttpGatewayTest {

    private final ObjectMapper mapper = Json.newMapper();

    @TempDir
    Path tempDir;

    private HttpGateway gateway;
    private UnixSocketServer socketServer;
    private Thread socketThread;

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.stop();
        }
        if (socketServer != null) {
            socketServer.stop();
        }
        if (socketThread != null) {
            try {
                socketThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ========== 控制面转发（Unix Socket 外壳） ==========

    @Test
    void controlEndpointForwardsThroughUnixSocketAndReturns200() throws Exception {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        when(dispatcher.dispatch(any(JsonNode.class), any()))
                .thenReturn(Map.of("ok", true, "data", Map.of("name", "HelloMusic")));

        HttpResponse<String> resp = postTo(gatewayAt(dispatcher), "{\"cmd\":\"info\"}");

        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("ok").asBoolean());
        assertEquals("HelloMusic", body.get("data").get("name").asText());
    }

    @Test
    void controlEndpointMapsErrorStatusToHttpCode() throws Exception {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        when(dispatcher.dispatch(any(JsonNode.class), any()))
                .thenReturn(Map.of("ok", false, "error", "unknown", "status", 404));

        HttpResponse<String> resp = postTo(gatewayAt(dispatcher), "{\"cmd\":\"music.get\",\"id\":\"nope\"}");

        assertEquals(404, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertFalse(body.get("ok").asBoolean());
        assertEquals("unknown", body.get("error").asText());
    }

    @Test
    void controlEndpointInjectsObservedRemoteAddrIntoForwardedRequest() throws Exception {
        // HTTP 外壳应把观测到的对端地址写入保留字段 @remoteAddr，服务端剥离后作为 remoteAddr
        // 传给 dispatcher（而非一律记 "local"）。这里捕获 dispatcher 收到的第二参验证之。
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        final String[] capturedRemote = new String[1];
        when(dispatcher.dispatch(any(JsonNode.class), any()))
                .thenAnswer(inv -> {
                    capturedRemote[0] = inv.getArgument(1, String.class);
                    return Map.of("ok", true, "data", Map.of());
                });

        postTo(gatewayAt(dispatcher), "{\"cmd\":\"info\"}");

        assertTrue(capturedRemote[0] != null && !"local".equals(capturedRemote[0]),
                "HTTP 外壳应注入观测到的真实对端地址（而非让服务端回退为 local）");
    }

    @Test
    void controlEndpointReturns502WhenSocketUnavailable() throws Exception {
        // 外壳指向一个不存在的 socket：连接失败应映射为 502，而不是 500 或空响应
        Path missing = tempDir.resolve("no-such.sock");
        gateway = new HttpGateway(0, null, null, new UnixSocketClient(missing, mapper), mapper, null);
        gateway.start();

        HttpResponse<String> resp = postTo(gateway, "{\"cmd\":\"info\"}");

        assertEquals(502, resp.statusCode(), "控制面不可达应返回 502");
        assertFalse(mapper.readTree(resp.body()).get("ok").asBoolean());
    }

    // ========== 控制面入口校验（在转发之前） ==========

    @Test
    void controlEndpointRejectsMalformedJson() throws Exception {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);

        HttpResponse<String> resp = postTo(gatewayAt(dispatcher), "not-json");

        assertEquals(400, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertFalse(body.get("ok").asBoolean());
    }

    @Test
    void controlEndpointRejectsNonPostMethod() throws Exception {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        HttpGateway g = gatewayAt(dispatcher);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + g.getPort() + "/control"))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, resp.statusCode());
    }

    @Test
    void controlEndpointRejectsOversizedBody() throws Exception {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);

        String big = "{\"cmd\":\"x\",\"pad\":\"" + "a".repeat(HttpGateway.MAX_CONTROL_BODY_BYTES) + "\"}";

        HttpResponse<String> resp = postTo(gatewayAt(dispatcher), big);

        assertEquals(413, resp.statusCode(), "超过上限的请求体应返回 413，而非被整体读入内存");
        JsonNode body = mapper.readTree(resp.body());
        assertFalse(body.get("ok").asBoolean());
    }

    // ========== 数据面（媒体） ==========

    @Test
    void multiRangeRequestReturnsOnlyFirstRange() throws Exception {
        // 多段 Range 请求应退化为「只返回第一段」，而非折叠成覆盖区间：折叠会返回客户端
        // 并未请求的中间字节，并让 Content-Range 谎报连续区间，违反 RFC 7233。此测试守护该约定。
        byte[] content = new byte[200_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        Path tmp = Files.createTempFile("hellomusic-range", ".mp3");
        Files.write(tmp, content);
        tmp.toFile().deleteOnExit();

        MusicFile musicFile = new MusicFile("A", "test.mp3", tmp.toString(), content.length,
                "mp3", LocalDateTime.now());
        MusicScannerService scanner = mock(MusicScannerService.class);
        when(scanner.getMusicById("A")).thenReturn(Optional.of(musicFile));
        PreloadService preload = mock(PreloadService.class); // getPreloaded 返回 null → 走流式路径

        gateway = new HttpGateway(0, scanner, preload, null, mapper, null);
        gateway.start();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + gateway.getPort() + "/stream/A"))
                .header("Range", "bytes=0-99,100000-100099")
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(206, resp.statusCode());
        assertEquals("bytes 0-99/" + content.length,
                resp.headers().firstValue("Content-Range").orElse(null),
                "多段请求应只返回第一段，Content-Range 只描述第一段");
        assertEquals(100, resp.body().length,
                "响应体应只含第一段 100 字节，而非折叠成覆盖区间的 100100 字节");
        for (int i = 0; i < 100; i++) {
            assertEquals((byte) i, resp.body()[i], "第一段字节内容应正确（0..99）");
        }
    }

    // ========== 控制面鉴权 ==========

    @Test
    void controlEndpointRejectsRequestWithoutTokenWhenConfigured() throws Exception {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        HttpGateway g = gatewayAt(dispatcher, "secret-token");

        HttpResponse<String> resp = postTo(g, "{\"cmd\":\"info\"}");

        assertEquals(401, resp.statusCode(), "配置了 token 后，无令牌请求应返回 401");
    }

    @Test
    void controlEndpointRejectsWrongToken() throws Exception {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        HttpGateway g = gatewayAt(dispatcher, "secret-token");

        HttpResponse<String> resp = postTo(g, "{\"cmd\":\"info\"}", "Bearer wrong-token");

        assertEquals(401, resp.statusCode(), "错误令牌应返回 401");
    }

    @Test
    void controlEndpointAcceptsCorrectToken() throws Exception {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        when(dispatcher.dispatch(any(JsonNode.class), any()))
                .thenReturn(Map.of("ok", true, "data", Map.of("name", "HelloMusic")));
        HttpGateway g = gatewayAt(dispatcher, "secret-token");

        HttpResponse<String> resp = postTo(g, "{\"cmd\":\"info\"}", "Bearer secret-token");

        assertEquals(200, resp.statusCode(), "正确令牌应放行并正常转发");
        assertEquals("HelloMusic", mapper.readTree(resp.body()).get("data").get("name").asText());
    }

    @Test
    void controlEndpointWithoutTokenConfiguredKeepsBackwardCompatibility() throws Exception {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        when(dispatcher.dispatch(any(JsonNode.class), any()))
                .thenReturn(Map.of("ok", true, "data", Map.of("name", "HelloMusic")));
        HttpGateway g = gatewayAt(dispatcher); // 未配置 token

        HttpResponse<String> resp = postTo(g, "{\"cmd\":\"info\"}");

        assertEquals(200, resp.statusCode(), "未配置 token 时保持无鉴权（向后兼容）");
    }

    // ========== 测试辅助 ==========

    /** 起真实 Unix Socket 控制面（内嵌 mock dispatcher）+ 指向它的 HTTP 网关，返回已绑定端口的网关。 */
    private HttpGateway gatewayAt(CommandDispatcher dispatcher) throws Exception {
        return gatewayAt(dispatcher, null);
    }

    private HttpGateway gatewayAt(CommandDispatcher dispatcher, String controlToken) throws Exception {
        Path sock = tempDir.resolve("control-" + System.nanoTime() + ".sock");
        socketServer = new UnixSocketServer(sock, dispatcher, mapper);
        socketThread = new Thread(socketServer::start, "http-test-socket");
        socketThread.start();
        awaitBound(socketServer);

        gateway = new HttpGateway(0, null, null, new UnixSocketClient(sock, mapper), mapper, controlToken);
        gateway.start();
        return gateway;
    }

    private static void awaitBound(UnixSocketServer server) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !server.isBound()) {
            Thread.sleep(10);
        }
        assertTrue(server.isBound(), "Unix Socket 控制面未在 5 秒内完成 bind");
    }

    private static HttpResponse<String> postTo(HttpGateway g, String body) throws Exception {
        return postTo(g, body, null);
    }

    private static HttpResponse<String> postTo(HttpGateway g, String body, String authorization)
            throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + g.getPort() + "/control"))
                .header("Content-Type", "application/json");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
