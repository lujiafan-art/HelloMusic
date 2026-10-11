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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
public class ConfigManager {
    private static final String CONFIG_FILE = "hellomusic-config.json";

    /**
     * 当前生效的配置。每次更新都整体替换引用，用 volatile 保证扫描线程、广播线程与
     * Tomcat 请求线程都能立刻看到新值。
     */
    private volatile Config config;

    private final ObjectMapper objectMapper;
    private final Validator validator;

    /**
     * 注入 Spring 托管的 ObjectMapper，保证序列化行为（含 Java 8 时间类型、未知字段容错）全局一致，
     * 避免各处自行 {@code new ObjectMapper()} 造成配置差异。
     *
     * <p>{@link Validator} 用于读取配置文件时做一次逐字段体检：文件可能来自旧版本（缺少新字段）
     * 或被手工改坏，非法项应回退到默认值，而不是让服务带着坏配置跑起来。
     */
    public ConfigManager(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        loadConfig();
    }

    private void loadConfig() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            config = createDefaultConfig();
            saveConfig();
            System.out.println("📝 Created default configuration file: " + CONFIG_FILE);
            return;
        }
        try {
            Config loaded = objectMapper.readValue(configFile, Config.class);
            if (loaded == null) {
                // 文件内容是字面量 null，当作空配置处理
                System.err.println("⚠️  " + CONFIG_FILE + " 没有配置内容，改用默认配置");
                config = createDefaultConfig();
                return;
            }
            // 非法项置空 → 由 mergeInto 补回默认值，只回退出错的字段
            clearInvalidFields(loaded);
            config = mergeInto(createDefaultConfig(), loaded);
            System.out.println("✅ Configuration loaded from: " + CONFIG_FILE);
        } catch (IOException e) {
            System.err.println("❌ Failed to load config, using defaults: " + e.getMessage());
            config = createDefaultConfig();
        }
    }

    /**
     * 逐字段体检，把非法项置空并打印告警。
     *
     * <p>用「置空」而不是「整份丢弃」，是为了只回退写错的那一项、其余自定义设置照常生效。
     * Bean Validation 对所有约束都把 {@code null} 视为合法，所以这里不会误伤
     * 「文件里根本没写这个字段」的正常情况。
     */
    private void clearInvalidFields(Config candidate) {
        for (ConstraintViolation<Config> violation : validator.validate(candidate)) {
            String path = violation.getPropertyPath().toString();
            int bracket = path.indexOf('[');
            // 集合元素的约束（如 supportedExtensions[0]）整份回退到容器字段
            String field = bracket < 0 ? path : path.substring(0, bracket);
            System.err.println("⚠️  " + CONFIG_FILE + " 中配置项非法，已回退为默认值: "
                    + path + " — " + violation.getMessage());
            clearField(candidate, field);
        }
    }

    private static void clearField(Config config, String field) {
        switch (field) {
            case "musicLibraryPaths" -> config.musicLibraryPaths = null;
            case "serverPort" -> config.serverPort = null;
            case "broadcastPort" -> config.broadcastPort = null;
            case "broadcastInterval" -> config.broadcastInterval = null;
            case "supportedExtensions" -> config.supportedExtensions = null;
            case "serverName" -> config.serverName = null;
            case "lastPlayMode" -> config.lastPlayMode = null;
            default -> { /* 未映射的字段：已告警，值保持原样 */ }
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
        defaultConfig.setLastPlayMode("FORWARD");  // 默认播放模式
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

    /**
     * 局部更新配置：只覆盖入参中「显式提供」的字段，未提交的字段保持当前值。
     *
     * <p>入参在到达这里之前已由 {@code @Valid} 做过 Bean Validation，
     * 非法取值会被拦下并返回 400，因此这里只需处理「哪些字段要改」。
     */
    public void setConfig(Config patch) {
        this.config = mergeInto(this.config, patch);
        saveConfig();
    }

    /**
     * 以 {@code base} 为基础，用 {@code patch} 中显式提供的字段覆盖它；{@code patch} 里为
     * {@code null} 的字段保持 {@code base} 的值不变。
     *
     * <p>数字字段刻意用包装类型：JSON 里缺失的字段会被反序列化成 {@code null}，
     * 从而能与「显式传 0」区分开——前者保留旧值，后者由 Bean Validation 判为非法并返回 400。
     *
     * <p>这也是 {@code PUT /api/config} 从「整体替换」改为「局部更新」的实现：
     * 只提交一个字段不会再让其余字段变成 0 / {@code null}。
     */
    static Config mergeInto(Config base, Config patch) {
        if (patch == null) {
            return base;
        }
        if (base == null) {
            return patch;
        }
        Config merged = new Config();
        merged.musicLibraryPaths = patch.musicLibraryPaths != null
                ? patch.musicLibraryPaths : base.musicLibraryPaths;
        merged.serverPort = patch.serverPort != null ? patch.serverPort : base.serverPort;
        merged.broadcastPort = patch.broadcastPort != null ? patch.broadcastPort : base.broadcastPort;
        merged.broadcastInterval = patch.broadcastInterval != null
                ? patch.broadcastInterval : base.broadcastInterval;
        merged.supportedExtensions = patch.supportedExtensions != null
                ? patch.supportedExtensions : base.supportedExtensions;
        merged.serverName = patch.serverName != null ? patch.serverName : base.serverName;
        merged.lastPlayMode = patch.lastPlayMode != null ? patch.lastPlayMode : base.lastPlayMode;
        return merged;
    }

    // ========== 新增：播放模式持久化 ==========

    public void savePlayMode(String mode) {
        config.setLastPlayMode(mode);
        saveConfig();
    }

    public String getLastPlayMode() {
        return config.getLastPlayMode() != null ? config.getLastPlayMode() : "FORWARD";
    }

    // ========================================

    /**
     * 配置项。
     *
     * <p>约束刻意使用「对 {@code null} 宽容」的注解（{@code @Min}/{@code @Max}/{@code @Size}/
     * {@code @Pattern}），因为 {@code PUT /api/config} 允许只提交要改的字段：字段缺省即
     * {@code null}，表示「不改这一项」。只有显式提供了取值、但取值非法（端口 0、间隔过小、
     * 列表为空、名字纯空白……）时才判为 400。
     */
    public static class Config {
        /** 音乐库目录列表；至少一个，元素不能是空白字符串。 */
        @Size(min = 1, message = "至少需要配置一个音乐库目录")
        private List<@NotBlank(message = "音乐库目录不能是空字符串") String> musicLibraryPaths;

        @Min(value = 1, message = "端口取值范围为 1-65535")
        @Max(value = 65535, message = "端口取值范围为 1-65535")
        private Integer serverPort;

        @Min(value = 1, message = "端口取值范围为 1-65535")
        @Max(value = 65535, message = "端口取值范围为 1-65535")
        private Integer broadcastPort;

        /** 广播间隔（毫秒）。下限 1000 避免把局域网刷爆，上限 1 小时。 */
        @Min(value = 1000, message = "广播间隔取值范围为 1000-3600000 毫秒")
        @Max(value = 3600000, message = "广播间隔取值范围为 1000-3600000 毫秒")
        private Integer broadcastInterval;

        /** 参与扫描的文件扩展名（不含点号）；至少一个，元素不能是空白字符串。 */
        @Size(min = 1, message = "受支持扩展名不能为空")
        private List<@NotBlank(message = "扩展名不能是空字符串") String> supportedExtensions;

        @Pattern(regexp = ".*\\S.*", message = "服务器名不能为空或纯空白")
        private String serverName;

        @Pattern(regexp = "(?i)FORWARD|REVERSE|SINGLE|SHUFFLE",
                message = "播放模式只支持 FORWARD / REVERSE / SINGLE / SHUFFLE")
        private String lastPlayMode;  // 新增：保存上次播放模式

        public List<String> getMusicLibraryPaths() { return musicLibraryPaths; }
        public void setMusicLibraryPaths(List<String> musicLibraryPaths) {
            this.musicLibraryPaths = musicLibraryPaths;
        }
        public Integer getServerPort() { return serverPort; }
        public void setServerPort(Integer serverPort) { this.serverPort = serverPort; }
        public Integer getBroadcastPort() { return broadcastPort; }
        public void setBroadcastPort(Integer broadcastPort) { this.broadcastPort = broadcastPort; }
        public Integer getBroadcastInterval() { return broadcastInterval; }
        public void setBroadcastInterval(Integer broadcastInterval) {
            this.broadcastInterval = broadcastInterval;
        }
        public List<String> getSupportedExtensions() { return supportedExtensions; }
        public void setSupportedExtensions(List<String> supportedExtensions) {
            this.supportedExtensions = supportedExtensions;
        }
        public String getServerName() { return serverName; }
        public void setServerName(String serverName) { this.serverName = serverName; }
        public String getLastPlayMode() { return lastPlayMode; }
        public void setLastPlayMode(String lastPlayMode) { this.lastPlayMode = lastPlayMode; }
    }
}
