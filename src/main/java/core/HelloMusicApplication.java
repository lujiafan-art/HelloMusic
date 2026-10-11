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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
@EnableScheduling
public class HelloMusicApplication {

    private static final String CONFIG_FILE = "hellomusic-config.json";
    private static final int DEFAULT_SERVER_PORT = 8080;
    private static final int DEFAULT_BROADCAST_PORT = 9999;

    public static void main(String[] args) {
        Map<String, Object> defaults = loadBootstrapProperties();

        SpringApplication app = new SpringApplication(HelloMusicApplication.class);
        // 以「默认属性」形式注入：优先级低于命令行参数，但足以让配置文件的端口生效
        app.setDefaultProperties(defaults);
        app.run(args);

        int serverPort = (int) defaults.getOrDefault("server.port", DEFAULT_SERVER_PORT);
        int broadcastPort = (int) defaults.getOrDefault("broadcast.port", DEFAULT_BROADCAST_PORT);
        printStartupBanner(serverPort, broadcastPort);
    }

    /**
     * 在 Spring 启动前读取 hellomusic-config.json，把 serverPort / broadcastPort / broadcastInterval
     * 接入 Spring 属性体系。
     *
     * <p>Spring Boot 只认 {@code application.properties} 里的 {@code server.port}，
     * 不会自动读取本项目的 JSON 配置文件；这里做一次桥接，让「改配置文件里的端口」真正生效。</p>
     */
    private static Map<String, Object> loadBootstrapProperties() {
        Map<String, Object> properties = new HashMap<>();
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            return properties;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(configFile);
            if (root.hasNonNull("serverPort")) {
                properties.put("server.port", root.get("serverPort").asInt());
            }
            if (root.hasNonNull("broadcastPort")) {
                properties.put("broadcast.port", root.get("broadcastPort").asInt());
            }
            if (root.hasNonNull("broadcastInterval")) {
                properties.put("broadcast.interval", root.get("broadcastInterval").asInt());
            }
        } catch (Exception e) {
            System.err.println("⚠️  读取 " + CONFIG_FILE + " 失败，将使用默认端口: " + e.getMessage());
        }
        return properties;
    }

    private static void printStartupBanner(int serverPort, int broadcastPort) {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════╗");
        System.out.println("║       HelloMusic Server Started           ║");
        System.out.println("║                                           ║");
        System.out.println("║" + pad("  API: http://localhost:" + serverPort + "/api") + "║");
        System.out.println("║" + pad("  CLI: Type 'help' in console") + "║");
        System.out.println("║" + pad("  Broadcast: UDP " + broadcastPort) + "║");
        System.out.println("╚═══════════════════════════════════════════╝");
        System.out.println();
    }

    /** 右侧补空格到固定宽度，保证边框对齐。 */
    private static String pad(String text) {
        final int width = 43;
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }
}
