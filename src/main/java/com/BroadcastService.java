package com;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

@Service
public class BroadcastService {
    private final ConfigManager configManager;
    private final ObjectMapper objectMapper;
    private String serverInfo;

    @Autowired
    public BroadcastService(ConfigManager configManager) {
        this.configManager = configManager;
        this.objectMapper = new ObjectMapper();
        updateServerInfo();
    }

    private void updateServerInfo() {
        try {
            Map<String, Object> info = new HashMap<>();
            info.put("type", "HelloMusic");
            info.put("version", "1.0.0");
            info.put("name", configManager.getConfig().getServerName());
            info.put("port", configManager.getConfig().getServerPort());
            info.put("librarySize", 0);
            info.put("timestamp", System.currentTimeMillis());
            info.put("api", "/api");

            this.serverInfo = objectMapper.writeValueAsString(info);
        } catch (Exception e) {
            this.serverInfo = "{\"type\":\"HelloMusic\",\"port\":8080}";
        }
    }

    @Scheduled(fixedDelayString = "${broadcast.interval:30000}")
    public void broadcast() {
        try {
            updateServerInfo();
            ConfigManager.Config config = configManager.getConfig();
            int broadcastPort = config.getBroadcastPort();

            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
            byte[] data = serverInfo.getBytes();
            DatagramPacket packet = new DatagramPacket(
                    data, data.length, broadcastAddress, broadcastPort
            );

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                socket.send(packet);
                System.out.println("📡 Broadcast sent to " + broadcastAddress + ":" + broadcastPort);
            }

            try {
                InetAddress localHost = InetAddress.getLocalHost();
                byte[] ip = localHost.getAddress();
                if (ip.length == 4) {
                    ip[3] = -1;
                    InetAddress subnetBroadcast = InetAddress.getByAddress(ip);
                    packet = new DatagramPacket(data, data.length, subnetBroadcast, broadcastPort);
                    try (DatagramSocket socket = new DatagramSocket()) {
                        socket.setBroadcast(true);
                        socket.send(packet);
                    }
                }
            } catch (Exception ignored) {}

        } catch (IOException e) {
            System.err.println("❌ Broadcast failed: " + e.getMessage());
        }
    }

    public void updateLibrarySize(int size) {
        try {
            Map<String, Object> info = objectMapper.readValue(serverInfo, Map.class);
            info.put("librarySize", size);
            this.serverInfo = objectMapper.writeValueAsString(info);
        } catch (Exception e) {
            // Ignore
        }
    }

    public void sendBroadcastNow() {
        broadcast();
    }
}