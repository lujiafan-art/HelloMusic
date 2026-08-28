package com;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
public class ConfigManager {
    private static final String CONFIG_FILE = "hellomusic-config.json";
    private Config config;
    private final ObjectMapper objectMapper;

    public ConfigManager() {
        this.objectMapper = new ObjectMapper();
        loadConfig();
    }

    private void loadConfig() {
        File configFile = new File(CONFIG_FILE);
        if (configFile.exists()) {
            try {
                config = objectMapper.readValue(configFile, Config.class);
                System.out.println("✅ Configuration loaded from: " + CONFIG_FILE);
            } catch (IOException e) {
                System.err.println("❌ Failed to load config, using defaults: " + e.getMessage());
                config = createDefaultConfig();
            }
        } else {
            config = createDefaultConfig();
            saveConfig();
            System.out.println("📝 Created default configuration file: " + CONFIG_FILE);
        }
    }

    private Config createDefaultConfig() {
        Config defaultConfig = new Config();
        defaultConfig.setMusicLibraryPaths(new ArrayList<>(List.of(
                Paths.get(System.getProperty("user.home"), "Music").toString()
        )));
        defaultConfig.setServerPort(8080);
        defaultConfig.setBroadcastPort(9999);
        defaultConfig.setBroadcastInterval(30000);
        defaultConfig.setSupportedExtensions(List.of("mp3", "mkv", "flac", "wav", "m4a", "ogg"));
        defaultConfig.setServerName("HelloMusic");
        return defaultConfig;
    }

    public void saveConfig() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(CONFIG_FILE), config);
            System.out.println("💾 Configuration saved to: " + CONFIG_FILE);
        } catch (IOException e) {
            System.err.println("❌ Failed to save config: " + e.getMessage());
        }
    }

    public Config getConfig() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
        saveConfig();
    }

    public static class Config {
        private List<String> musicLibraryPaths;
        private int serverPort;
        private int broadcastPort;
        private int broadcastInterval;
        private List<String> supportedExtensions;
        private String serverName;

        public List<String> getMusicLibraryPaths() { return musicLibraryPaths; }
        public void setMusicLibraryPaths(List<String> musicLibraryPaths) {
            this.musicLibraryPaths = musicLibraryPaths;
        }
        public int getServerPort() { return serverPort; }
        public void setServerPort(int serverPort) { this.serverPort = serverPort; }
        public int getBroadcastPort() { return broadcastPort; }
        public void setBroadcastPort(int broadcastPort) { this.broadcastPort = broadcastPort; }
        public int getBroadcastInterval() { return broadcastInterval; }
        public void setBroadcastInterval(int broadcastInterval) {
            this.broadcastInterval = broadcastInterval;
        }
        public List<String> getSupportedExtensions() { return supportedExtensions; }
        public void setSupportedExtensions(List<String> supportedExtensions) {
            this.supportedExtensions = supportedExtensions;
        }
        public String getServerName() { return serverName; }
        public void setServerName(String serverName) { this.serverName = serverName; }
    }
}