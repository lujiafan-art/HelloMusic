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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BroadcastService {
    private final ConfigManager configManager;
    private final MusicScannerService scannerService;
    private final ObjectMapper objectMapper;

    @Autowired
    public BroadcastService(ConfigManager configManager,
                            MusicScannerService scannerService,
                            ObjectMapper objectMapper) {
        this.configManager = configManager;
        this.scannerService = scannerService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${broadcast.interval:30000}")
    public void broadcast() {
        final byte[] data;
        try {
            // 每次广播都重新构造负载，librarySize 等字段始终反映当前状态
            data = buildServerInfo().getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            System.err.println("❌ Broadcast payload build failed: " + e.getMessage());
            return;
        }

        ConfigManager.Config config = configManager.getConfig();
        int broadcastPort = config.getBroadcastPort();

        try {
            sendTo(InetAddress.getByName("255.255.255.255"), broadcastPort, data);
            System.out.println("📡 Broadcast sent to 255.255.255.255:" + broadcastPort);
        } catch (IOException e) {
            System.err.println("❌ Broadcast failed: " + e.getMessage());
        }

        // 额外向本机所在子网的广播地址发送一次，兼容不转发 255.255.255.255 的网络
        try {
            byte[] ip = InetAddress.getLocalHost().getAddress();
            if (ip.length == 4) {
                ip[3] = -1;
                sendTo(InetAddress.getByAddress(ip), broadcastPort, data);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 构造广播负载。librarySize 取自扫描服务的实时库大小。
     *
     * <p>原实现把 librarySize 写死为 0，且 {@code broadcast()} 每次都会调用该方法覆盖，
     * 导致 {@code updateLibrarySize()} 写入的真实数量立刻被重置回 0 —— 已修正为动态读取。</p>
     */
    private String buildServerInfo() throws JsonProcessingException {
        ConfigManager.Config config = configManager.getConfig();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("type", "HelloMusic");
        info.put("version", "1.0.0");
        info.put("name", config.getServerName());
        info.put("port", config.getServerPort());
        info.put("librarySize", scannerService.getLibrarySize());
        info.put("timestamp", System.currentTimeMillis());
        info.put("api", "/api");
        return objectMapper.writeValueAsString(info);
    }

    private void sendTo(InetAddress address, int port, byte[] data) throws IOException {
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.send(packet);
        }
    }

    public void sendBroadcastNow() {
        broadcast();
    }
}
