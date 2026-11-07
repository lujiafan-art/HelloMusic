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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigManager {
    private static final String CONFIG_FILE = "hellomusic-config.json";
    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    /**
     * 元数据解析的默认线程数：取可用处理器核数，至少为 1。
     *
     * <p>为 1 时解析退化为串行——这在单核机器上正好是合理的取值，也便于排查
     * 「并行才出现的」问题。用户可通过配置项 {@code scanThreads} 覆盖。</p>
     */
    public static final int DEFAULT_SCAN_THREADS =
            Math.max(1, Runtime.getRuntime().availableProcessors());

    /** {@code scanThreads} 的上限，避免把机器压垮（也避免配置写错一个 0 就开 1000 个线程）。 */
    public static final int MAX_SCAN_THREADS = 64;

    // ===== 用户选项（原 UserConfig 的 7 个开关式选项迁入此处，单一 JSON 配置统一管理） =====

    /** 播放队列最多容纳的歌曲数，超出部分在加载/添加时被丢弃。 */
    public static final int DEFAULT_MAX_QUEUE = 200;
    /** {@code maxQueueSize} 的上限，防止配置写错一个天文数字把内存吃光。 */
    public static final int MAX_MAX_QUEUE = 100_000;
    /** 扫描并读取音乐文件的目录；支持 {@code ~} 与英文逗号分隔多个。 */
    public static final String DEFAULT_MUSIC_PATH = "~/Music";
    /** 日志文件目录；文件名由 {@link #logFileName(LocalDateTime)} 按启动时间生成。 */
    public static final String DEFAULT_LOG_PATH = "~/.local/share/HelloMusic/logs";
    /** Unix Socket 文件路径：本机客户端通过它连接（HTTP over Unix domain socket）。 */
    public static final String DEFAULT_UNIX_SOCKET_PATH = "~/.local/share/HelloMusic/hellomusic.sock";
    /** 曲库自动重扫的默认间隔（毫秒）：5 分钟。 */
    public static final int DEFAULT_RESCAN_INTERVAL = 300_000;

    /**
     * 当前生效的配置。每次更新都整体替换引用，用 volatile 保证扫描线程、广播线程与
     * Tomcat 请求线程都能立刻看到新值。
     */
    private volatile Config config;

    /**
     * 保护「读 config 引用 → 派生新配置 → 写回引用并落盘」这一段 read-modify-write 的锁。
     *
     * <p>{@link #setConfig} 与 {@link #savePlayMode} 都会改配置并写盘，必须串行化：否则两者并发时
     * 可能各自基于旧快照算出新值、互相覆盖对方已落盘的改动（例如刚保存的播放模式被一次配置更新冲掉，
     * 或配置字段被模式切换回滚）。锁只包住「派生 + 写回 + 落盘」这一复合步骤即可，读取方仍走
     * volatile 的 {@code config} 引用，不受影响。</p>
     */
    private final Object writeLock = new Object();

    private final ObjectMapper objectMapper;
    private final Validator validator;

    /**
     * 传入统一的 ObjectMapper，保证序列化行为（含 Java 8 时间类型、未知字段容错）全局一致，
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
            log.info("已创建默认配置文件: " + CONFIG_FILE);
            return;
        }
        try {
            Config loaded = objectMapper.readValue(configFile, Config.class);
            if (loaded == null) {
                // 文件内容是字面量 null：与「文件不存在」保持一致，落一份默认配置
                log.warn(CONFIG_FILE + " 没有配置内容，改用默认配置");
                config = createDefaultConfig();
                saveConfig();
                return;
            }
            // 非法项回退（顶层整项 / 集合元素单项），再由 mergeInto 补回默认值
            clearInvalidFields(loaded);
            config = mergeInto(createDefaultConfig(), loaded);
            log.info("已从 " + CONFIG_FILE + " 加载配置");
        } catch (IOException e) {
            // 文件存在但解析失败：先隔离损坏文件（改名备份，不静默覆盖用户数据），
            // 再落一份默认配置，使下次启动不再反复报错。
            log.error("加载配置失败（" + CONFIG_FILE + " 已损坏），已备份损坏文件并改用默认值: "
                    + e.getMessage());
            quarantineCorruptConfig(configFile);
            config = createDefaultConfig();
            saveConfig();
        }
    }

    /** 把损坏的配置文件改名为带时间戳的副本，避免被默认配置覆盖，便于用户事后排查。 */
    private void quarantineCorruptConfig(File configFile) {
        try {
            File backup = new File(configFile.getParentFile(),
                    configFile.getName() + ".corrupt-" + System.currentTimeMillis());
            if (!configFile.renameTo(backup)) {
                log.warn("备份损坏配置文件失败，将直接覆盖: " + configFile.getName());
            }
        } catch (Exception e) {
            log.warn("备份损坏配置文件失败: " + e.getMessage());
        }
    }

    /**
     * 逐字段体检，把非法项回退为默认值并打印告警。
     *
     * <p>顶层字段整项回退；集合元素（如 {@code supportedExtensions[0]}、{@code musicPaths[1]}）
     * 只剔除写错的那一项、保留其余合法项，避免「一个空元素导致整个列表回退默认、丢掉用户其余合法值」。
     * Bean Validation 对所有约束都把 {@code null} 视为合法，所以这里不会误伤
     * 「文件里根本没写这个字段」的正常情况。</p>
     */
    private void clearInvalidFields(Config candidate) {
        for (ConstraintViolation<Config> violation : validator.validate(candidate)) {
            String path = violation.getPropertyPath().toString();
            int bracket = path.indexOf('[');
            if (bracket < 0) {
                log.warn(CONFIG_FILE + " 中配置项非法，已回退为默认值: "
                        + path + " — " + violation.getMessage());
                clearField(candidate, path);
            } else {
                // 集合元素的约束：只剔除写错的那一项
                String field = path.substring(0, bracket);
                int idx = elementIndex(path, bracket);
                log.warn(CONFIG_FILE + " 中配置项非法，已剔除该项: "
                        + path + " — " + violation.getMessage());
                clearListElement(candidate, field, idx);
            }
        }
        // 元素级回退后统一剔除被标记的 null 项；整列都被剔除则置 null，交由 mergeInto 补默认
        candidate.supportedExtensions = stripNulls(candidate.supportedExtensions);
        candidate.musicPaths = stripNulls(candidate.musicPaths);
        candidate.playbackQueue = stripNulls(candidate.playbackQueue);
    }

    /** 解析集合元素下标，形如 {@code musicPaths[1]} → 1；解析失败返回 -1（不剔除）。 */
    private static int elementIndex(String path, int bracket) {
        int end = path.indexOf(']', bracket);
        try {
            return Integer.parseInt(path.substring(bracket + 1, end));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return -1;
        }
    }

    private static void clearListElement(Config config, String field, int idx) {
        List<String> list = switch (field) {
            case "supportedExtensions" -> config.supportedExtensions;
            case "musicPaths" -> config.musicPaths;
            case "playbackQueue" -> config.playbackQueue;
            default -> null;
        };
        if (list != null && idx >= 0 && idx < list.size()) {
            list.set(idx, null); // 标记待清理；随后由 stripNulls 统一剔除
        }
    }

    /** 剔除列表中的 null 项；若结果为空返回 {@code null}，交由 mergeInto 补默认值。 */
    private static List<String> stripNulls(List<String> list) {
        if (list == null) {
            return null;
        }
        List<String> out = list.stream().filter(Objects::nonNull).toList();
        return out.isEmpty() ? null : out;
    }

    private static void clearField(Config config, String field) {
        switch (field) {
            case "serverPort" -> config.serverPort = null;
            case "broadcastPort" -> config.broadcastPort = null;
            case "broadcastInterval" -> config.broadcastInterval = null;
            case "scanThreads" -> config.scanThreads = null;
            case "rescanInterval" -> config.rescanInterval = null;
            case "supportedExtensions" -> config.supportedExtensions = null;
            case "serverName" -> config.serverName = null;
            case "lastPlayMode" -> config.lastPlayMode = null;
            case "currentIndex" -> config.currentIndex = null;
            case "maxQueueSize" -> config.maxQueueSize = null;
            case "autoSaveQueue" -> config.autoSaveQueue = null;
            case "musicPaths" -> config.musicPaths = null;
            case "enableFileLog" -> config.enableFileLog = null;
            case "logPath" -> config.logPath = null;
            case "enableHeartbeat" -> config.enableHeartbeat = null;
            case "unixSocketPath" -> config.unixSocketPath = null;
            default -> { /* 未映射的字段：已告警，值保持原样 */ }
        }
    }

    private Config createDefaultConfig() {
        Config defaultConfig = new Config();
        defaultConfig.setServerPort(8080);
        defaultConfig.setBroadcastPort(9999);
        defaultConfig.setBroadcastInterval(30000);
        defaultConfig.setScanThreads(DEFAULT_SCAN_THREADS);
        defaultConfig.setRescanInterval(DEFAULT_RESCAN_INTERVAL);
        defaultConfig.setSupportedExtensions(List.of("mp3", "mkv", "flac", "wav", "m4a", "ogg"));
        defaultConfig.setServerName("HelloMusic");
        defaultConfig.setLastPlayMode("FORWARD");  // 默认播放模式
        defaultConfig.setPlaybackQueue(new ArrayList<>());  // 空队列
        defaultConfig.setCurrentIndex(0);
        // 用户选项默认值（原 UserConfig 的 7 项迁入）
        defaultConfig.setMaxQueueSize(DEFAULT_MAX_QUEUE);
        defaultConfig.setAutoSaveQueue(0);
        defaultConfig.setMusicPaths(new ArrayList<>(List.of(DEFAULT_MUSIC_PATH)));
        defaultConfig.setEnableFileLog(0);
        defaultConfig.setLogPath(DEFAULT_LOG_PATH);
        defaultConfig.setEnableHeartbeat(1);
        defaultConfig.setUnixSocketPath(DEFAULT_UNIX_SOCKET_PATH);
        return defaultConfig;
    }

    private void saveConfig() {
        try {
            // 原子写入：先写同目录临时文件再替换，避免崩溃时留下半截 JSON 导致配置被静默丢弃
            JsonFiles.writeAtomic(objectMapper.writerWithDefaultPrettyPrinter(),
                    new File(CONFIG_FILE), config);
            log.info("配置已保存至 " + CONFIG_FILE);
        } catch (IOException e) {
            log.warn("保存配置失败: " + e.getMessage());
        }
    }

    public Config getConfig() {
        return config;
    }

    /**
     * 局部更新配置：只覆盖入参中「显式提供」的字段，未提交的字段保持当前值。
     *
     * <p>入参在到达这里之前已由命令分发器用 {@code Validator} 做过 Bean Validation，
     * 非法取值会被拦下并返回 400 状态，因此这里只需处理「哪些字段要改」。
     */
    public void setConfig(Config patch) {
        synchronized (writeLock) {
            Config previous = this.config;
            Config merged = mergeInto(previous, patch);
            // server.port 与 broadcast.interval 在启动时即绑定到数据面/调度器，
            // 运行时修改只落盘、不立即生效——显式告警，避免用户误以为已即时生效。
            warnIfRestartRequired(previous, merged);
            this.config = merged;
            saveConfig();
        }
    }

    /** 对「需重启才生效」的字段变更给出告警（端口已绑定 / 定时间隔已解析）。 */
    private static void warnIfRestartRequired(Config before, Config after) {
        if (before == null || after == null) {
            return;
        }
        if (!Objects.equals(before.serverPort, after.serverPort)) {
            log.warn("serverPort 由 " + before.serverPort + " 改为 " + after.serverPort
                    + "，需重启服务后生效（当前监听端口不变）");
        }
        if (!Objects.equals(before.broadcastInterval, after.broadcastInterval)) {
            log.warn("broadcastInterval 由 " + before.broadcastInterval + " 改为 "
                    + after.broadcastInterval + "，需重启服务后生效（定时任务已绑定启动时值）");
        }
        if (!Objects.equals(before.rescanInterval, after.rescanInterval)) {
            log.warn("rescanInterval 由 " + before.rescanInterval + " 改为 "
                    + after.rescanInterval + "，需重启服务后生效（重扫调度已绑定启动时值）");
        }
    }

    /**
     * 以 {@code base} 为基础，用 {@code patch} 中显式提供的字段覆盖它；{@code patch} 里为
     * {@code null} 的字段保持 {@code base} 的值不变。
     *
     * <p>数字字段刻意用包装类型：JSON 里缺失的字段会被反序列化成 {@code null}，
     * 从而能与「显式传 0」区分开——前者保留旧值，后者由 Bean Validation 判为非法并返回 400。
     *
     * <p>这也是 {@code config.set} 命令从「整体替换」改为「局部更新」的实现：
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
        merged.serverPort = patch.serverPort != null ? patch.serverPort : base.serverPort;
        merged.broadcastPort = patch.broadcastPort != null ? patch.broadcastPort : base.broadcastPort;
        merged.broadcastInterval = patch.broadcastInterval != null
                ? patch.broadcastInterval : base.broadcastInterval;
        merged.scanThreads = patch.scanThreads != null ? patch.scanThreads : base.scanThreads;
        merged.rescanInterval = patch.rescanInterval != null ? patch.rescanInterval : base.rescanInterval;
        merged.supportedExtensions = copyList(patch.supportedExtensions != null
                ? patch.supportedExtensions : base.supportedExtensions);
        merged.serverName = patch.serverName != null ? patch.serverName : base.serverName;
        merged.lastPlayMode = patch.lastPlayMode != null ? patch.lastPlayMode : base.lastPlayMode;
        merged.playbackQueue = copyList(patch.playbackQueue != null
                ? patch.playbackQueue : base.playbackQueue);
        merged.currentIndex = patch.currentIndex != null ? patch.currentIndex : base.currentIndex;
        merged.maxQueueSize = patch.maxQueueSize != null ? patch.maxQueueSize : base.maxQueueSize;
        merged.autoSaveQueue = patch.autoSaveQueue != null ? patch.autoSaveQueue : base.autoSaveQueue;
        merged.musicPaths = copyList(patch.musicPaths != null ? patch.musicPaths : base.musicPaths);
        merged.enableFileLog = patch.enableFileLog != null ? patch.enableFileLog : base.enableFileLog;
        merged.logPath = patch.logPath != null ? patch.logPath : base.logPath;
        merged.enableHeartbeat = patch.enableHeartbeat != null ? patch.enableHeartbeat : base.enableHeartbeat;
        merged.unixSocketPath = patch.unixSocketPath != null ? patch.unixSocketPath : base.unixSocketPath;
        return merged;
    }

    /** 防御性拷贝 List：合并结果不与被合并对象共享引用，避免调用方事后改 patch 污染配置。 */
    private static List<String> copyList(List<String> src) {
        return src != null ? new ArrayList<>(src) : null;
    }

    // ========== 新增：播放模式持久化 ==========

    public void savePlayMode(String mode) {
        synchronized (writeLock) {
            // 走 mergeInto 产生新对象，而不是就地改 config 的字段：
            // 既与 setConfig 复用同一套「只读不改共享对象」的不可变更新语义，也避免「读旧快照 + 改共享
            // 字段 + 写盘」在并发下被另一路的 setConfig 覆盖，同时保证其它线程读到的是完整新值而非中间态。
            Config patch = new Config();
            patch.setLastPlayMode(mode);
            this.config = mergeInto(this.config, patch);
            saveConfig();
        }
    }

    public String getLastPlayMode() {
        return config.getLastPlayMode() != null ? config.getLastPlayMode() : "FORWARD";
    }

    // ========== 新增：播放队列持久化 ==========

    /**
     * 把当前播放队列（歌曲 ID 列表）与播放位置（队列下标）写入配置文件，重启后由
     * {@link #getPlaybackQueue()} / {@link #getCurrentIndex()} 读回，恢复上次听歌现场。
     *
     * <p>走与 {@link #savePlayMode} 相同的「局部 patch + mergeInto + 原子写盘」语义，
     * 不会冲掉其它配置项，也不与并发的 {@code setConfig} 互相覆盖。</p>
     */
    public void savePlaybackQueue(List<String> ids, int index) {
        synchronized (writeLock) {
            Config patch = new Config();
            patch.setPlaybackQueue(new ArrayList<>(ids));
            patch.setCurrentIndex(index);
            this.config = mergeInto(this.config, patch);
            saveConfig();
        }
    }

    /** 读取持久化的播放队列；未保存过则返回空列表（非 {@code null}，便于调用方直接遍历）。 */
    public List<String> getPlaybackQueue() {
        List<String> q = config.getPlaybackQueue();
        return q != null ? q : new ArrayList<>();
    }

    /** 读取持久化的播放位置（队列下标）；未保存过则返回 0。 */
    public int getCurrentIndex() {
        Integer i = config.getCurrentIndex();
        return i != null ? i : 0;
    }

    // ========== 用户选项解析型访问器（原 UserConfig 的职责并入此处） ==========

    /** 队列最大歌曲数；未配置时回退默认 {@link #DEFAULT_MAX_QUEUE}。 */
    public int getMaxQueueSize() {
        Integer v = config.getMaxQueueSize();
        return v != null ? v : DEFAULT_MAX_QUEUE;
    }

    /** 是否自动保存播放列表；未配置时回退默认关闭（0）。 */
    public boolean isAutoSaveQueue() {
        return flag(config.getAutoSaveQueue(), false);
    }

    /** 是否启用心跳包；未配置时回退默认开启（1）。 */
    public boolean isEnableHeartbeat() {
        return flag(config.getEnableHeartbeat(), true);
    }

    /**
     * 扫描并读取音乐文件的目录列表（已展开 {@code ~} 前缀、逐项去空白）。
     * 未配置或全部为空段时回退默认 {@code ~/Music}，保证扫描永远至少有一个目录。
     */
    public List<Path> getMusicPaths() {
        List<String> raw = config.getMusicPaths();
        List<Path> out = new ArrayList<>();
        if (raw != null) {
            for (String s : raw) {
                if (s == null || s.trim().isEmpty()) {
                    continue;
                }
                out.add(expandHome(s.trim(), defaultMusicPath()));
            }
        }
        if (out.isEmpty()) {
            out.add(defaultMusicPath());
        }
        return out;
    }

    /** Unix Socket 文件路径（已展开 {@code ~} 前缀）；未配置时回退默认 {@link #DEFAULT_UNIX_SOCKET_PATH}。 */
    public Path getUnixSocketPath() {
        return expandHome(config.getUnixSocketPath(), defaultUnixSocketPath());
    }

    /** 开关型取值 → boolean：1=开启，0=关闭，其它（含 null）回退 {@code fallback}。 */
    private static boolean flag(Integer v, boolean fallback) {
        return v != null ? v == 1 : fallback;
    }

    private static Path defaultMusicPath() {
        return Paths.get(System.getProperty("user.home"), "Music");
    }

    private static Path defaultUnixSocketPath() {
        return Paths.get(System.getProperty("user.home"),
                ".local", "share", "HelloMusic", "hellomusic.sock");
    }

    /**
     * 展开 {@code ~} 前缀；空值/相对路径按 {@code fallback} 解析。
     * 供启动前（无 Spring 上下文）与运行时共用。     */
    public static Path expandHome(String value, Path fallback) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return fallback;
        }
        if (v.equals("~") || v.startsWith("~/")) {
            return Paths.get(System.getProperty("user.home"))
                    .resolve(v.length() > 2 ? v.substring(2) : "");
        }
        return Paths.get(v);
    }

    /** 依据启动时刻生成日志文件名：{@code yyyy-MM-dd-HHmmss.txt}。 */
    public static String logFileName(LocalDateTime startupTime) {
        return startupTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".txt";
    }

    // ========================================

    /**
     * 配置项：服务器字段（端口/广播/扫描/扩展名）与用户选项（队列上限/自动保存/音乐目录/
     * 日志/心跳）统一收在此处，{@code hellomusic-config.json} 是<strong>唯一</strong>配置来源。
     *
     * <p>约束刻意使用「对 {@code null} 宽容」的注解（{@code @Min}/{@code @Max}/{@code @Size}/
     * {@code @Pattern}），因为 {@code config.set} 命令允许只提交要改的字段：字段缺省即
     * {@code null}，表示「不改这一项」。只有显式提供了取值、但取值非法（端口 0、间隔过小、
     * 列表为空、名字纯空白……）时才判为 400。
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 让旧版配置文件里已被移除的字段
     * （如历史上的 {@code musicLibraryPaths}、三文件体系的开关型选项等）不再触发反序列化报错，
     * 保证平滑升级。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Config {
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

        /**
         * 扫描时并行解析元数据的线程数。
         *
         * <p>元数据解析（逐个打开文件、读取标签与音频头）是整次扫描中最重的一步，
         * 因此单独开线程池并行执行。设为 {@code 1} 即退回串行解析。</p>
         */
        @Min(value = 1, message = "解析线程数取值范围为 1-64")
        @Max(value = 64, message = "解析线程数取值范围为 1-64")
        private Integer scanThreads;

        /** 曲库自动重扫间隔（毫秒）。默认 5 分钟；下限 10 秒避免疯狂重扫，上限 24 小时。 */
        @Min(value = 10000, message = "重扫间隔取值范围为 10000-86400000 毫秒")
        @Max(value = 86400000, message = "重扫间隔取值范围为 10000-86400000 毫秒")
        private Integer rescanInterval;

        /** 参与扫描的文件扩展名（不含点号）；至少一个，元素不能是空白字符串。 */
        @Size(min = 1, message = "受支持扩展名不能为空")
        private List<@NotBlank(message = "扩展名不能是空字符串") String> supportedExtensions;

        @Pattern(regexp = ".*\\S.*", message = "服务器名不能为空或纯空白")
        private String serverName;

        @Pattern(regexp = "(?i)FORWARD|REVERSE|SINGLE|SHUFFLE",
                message = "播放模式只支持 FORWARD / REVERSE / SINGLE / SHUFFLE")
        private String lastPlayMode;  // 新增：保存上次播放模式

        /** 持久化的播放队列（歌曲 ID 列表）；重启后恢复，避免每次都要重新载入。 */
        private List<String> playbackQueue;

        /**
         * 持久化的当前播放位置（队列下标）；与 {@link #playbackQueue} 一起在重启后恢复。
         *
         * <p>只校验下界（下标不能为负）。上界依赖 {@link #playbackQueue} 的长度，无法用固定
         * {@code @Max} 静态表达，故此处不设上限——越界值（大于队列长度）由
         * {@link PlaybackSession#setCurrentIndex(int)} 兜底：它仅在「队列非空且
         * {@code 0 <= index < size}」时生效，越界被静默忽略，不会导致运行时越界。</p>
         */
        @Min(value = 0, message = "播放位置下标不能为负数")
        private Integer currentIndex;  // 用包装类型：null 表示「未显式保存」，mergeInto 时保留旧值

        // ===== 用户选项（原 UserConfig 的 7 项迁入，均为「对 null 宽容」的约束） =====

        /** 播放队列最多容纳的歌曲数。 */
        @Min(value = 1, message = "队列最大音乐数取值范围为 1-100000")
        @Max(value = 100000, message = "队列最大音乐数取值范围为 1-100000")
        private Integer maxQueueSize;

        /** 是否把播放队列自动落盘（重启后恢复）。0=否（默认），1=是。 */
        @Min(value = 0, message = "是否自动保存列表只能为 0 或 1")
        @Max(value = 1, message = "是否自动保存列表只能为 0 或 1")
        private Integer autoSaveQueue;

        /** 扫描并读取音乐文件的目录列表；至少一个，元素不能是空白字符串。 */
        @Size(min = 1, message = "读取音乐的位置不能为空")
        private List<@NotBlank(message = "音乐目录不能是空字符串") String> musicPaths;

        /** 是否把运行日志额外写入文件。0=否（默认），1=是。 */
        @Min(value = 0, message = "是否输出日志只能为 0 或 1")
        @Max(value = 1, message = "是否输出日志只能为 0 或 1")
        private Integer enableFileLog;

        /** 日志文件目录；不存在会自动创建。 */
        @Pattern(regexp = ".*\\S.*", message = "日志位置不能是空字符串或纯空白")
        private String logPath;

        /** 是否启用心跳保活。0=否，1=是（默认）。 */
        @Min(value = 0, message = "是否启用心跳包只能为 0 或 1")
        @Max(value = 1, message = "是否启用心跳包只能为 0 或 1")
        private Integer enableHeartbeat;

        /** Unix Socket 文件路径：本机客户端通过它连接（HTTP over Unix domain socket）。 */
        @Pattern(regexp = ".*\\S.*", message = "Unix Socket 路径不能是空字符串或纯空白")
        private String unixSocketPath;

        public Integer getServerPort() { return serverPort; }
        public void setServerPort(Integer serverPort) { this.serverPort = serverPort; }
        public Integer getBroadcastPort() { return broadcastPort; }
        public void setBroadcastPort(Integer broadcastPort) { this.broadcastPort = broadcastPort; }
        public Integer getBroadcastInterval() { return broadcastInterval; }
        public void setBroadcastInterval(Integer broadcastInterval) {
            this.broadcastInterval = broadcastInterval;
        }
        public Integer getScanThreads() { return scanThreads; }
        public void setScanThreads(Integer scanThreads) { this.scanThreads = scanThreads; }
        public Integer getRescanInterval() { return rescanInterval; }
        public void setRescanInterval(Integer rescanInterval) { this.rescanInterval = rescanInterval; }
        public List<String> getSupportedExtensions() { return supportedExtensions; }
        public void setSupportedExtensions(List<String> supportedExtensions) {
            this.supportedExtensions = supportedExtensions;
        }
        public String getServerName() { return serverName; }
        public void setServerName(String serverName) { this.serverName = serverName; }
        public String getLastPlayMode() { return lastPlayMode; }
        public void setLastPlayMode(String lastPlayMode) { this.lastPlayMode = lastPlayMode; }
        public List<String> getPlaybackQueue() { return playbackQueue; }
        public void setPlaybackQueue(List<String> playbackQueue) { this.playbackQueue = playbackQueue; }
        public Integer getCurrentIndex() { return currentIndex; }
        public void setCurrentIndex(Integer currentIndex) { this.currentIndex = currentIndex; }
        public Integer getMaxQueueSize() { return maxQueueSize; }
        public void setMaxQueueSize(Integer maxQueueSize) { this.maxQueueSize = maxQueueSize; }
        public Integer getAutoSaveQueue() { return autoSaveQueue; }
        public void setAutoSaveQueue(Integer autoSaveQueue) { this.autoSaveQueue = autoSaveQueue; }
        public List<String> getMusicPaths() { return musicPaths; }
        public void setMusicPaths(List<String> musicPaths) { this.musicPaths = musicPaths; }
        public Integer getEnableFileLog() { return enableFileLog; }
        public void setEnableFileLog(Integer enableFileLog) { this.enableFileLog = enableFileLog; }
        public String getLogPath() { return logPath; }
        public void setLogPath(String logPath) { this.logPath = logPath; }
        public Integer getEnableHeartbeat() { return enableHeartbeat; }
        public void setEnableHeartbeat(Integer enableHeartbeat) { this.enableHeartbeat = enableHeartbeat; }
        public String getUnixSocketPath() { return unixSocketPath; }
        public void setUnixSocketPath(String unixSocketPath) { this.unixSocketPath = unixSocketPath; }
    }
}
