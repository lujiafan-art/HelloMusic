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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP 控制面测试：验证 {@code POST /control} 把 JSON 请求转发给
 * {@link CommandDispatcher}，并把响应里的 {@code status} 字段映射为真实的 HTTP 状态码。
 *
 * <p>用 {@code mock(CommandDispatcher.class)} 隔离开真实业务，只测传输层行为；
 * 网关以端口 {@code 0} 启动（系统分配临时端口），用 JDK 自带 {@link HttpClient} 发起真实请求。</p>
 */
class HttpGatewayTest {

    private final ObjectMapper mapper = Json.newMapper();
    private HttpGateway gateway;

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.stop();
        }
    }

    @Test
    void controlEndpointDispatchesAndReturns200() throws Exception {
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

    /** 启动一个网关（端口 0，媒体协作者置空），返回已绑定端口的网关。 */
    private HttpGateway gatewayAt(CommandDispatcher dispatcher) throws Exception {
        gateway = new HttpGateway(0, null, null, dispatcher, mapper);
        gateway.start();
        return gateway;
    }

    private static HttpResponse<String> postTo(HttpGateway g, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + g.getPort() + "/control"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
