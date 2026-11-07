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
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 纯 JDK 启动入口（脱离 Spring Boot）。
 *
 * <p>职责：读取配置 → 装文件日志 → 手动装配对象图 → 启动扫描与队列恢复 → 挂调度器
 * （广播 / 会话巡检 / 曲库重扫）→ 启动 Unix Socket 控制面（本机）与 HTTP 网关
 * （控制面 + 数据面媒体）→ 注册关闭钩子。</p>
 */
public final class HelloMusicApplication {

    private HelloMusicApplication() {
    }

    public static void main(String[] args) {
        // 启动前一次性读取配置 JSON：文件日志必须在日志系统初始化之前装好，
        // 才能把启动日志也写进文件。
        JsonNode configJson = readConfigJson();

        if (isFileLogEnabled(configJson)) {
            LoggingInstaller.install(computeLogFile(configJson));
        }

        ObjectMapper mapper = Json.newMapper();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        // ===== 手动装配对象图（依赖顺序：Config → Scanner → Playlist → Session → Queue → Engine → Service） =====
        ConfigManager configManager = new ConfigManager(mapper, validator);
        MusicScannerService scannerService = new MusicScannerService(configManager);
        PlaylistService playlistService = new PlaylistService(scannerService, mapper);
        SessionRegistry sessionRegistry = new SessionRegistry(scannerService, configManager);
        QueueManager queueManager = new QueueManager(sessionRegistry, scannerService, playlistService, configManager);
        ProgressMapper progressMapper = new ProgressMapper();

        // 预载与扫描共用一个后台线程池（异步短任务）
        ExecutorService executor = ThreadPools.bounded("hellomusic-worker", 8, 200,
                new ThreadPoolExecutor.CallerRunsPolicy());
        PreloadService preloadService = new PreloadService(scannerService, executor);
        PlaybackEngine playbackEngine = new PlaybackEngine(sessionRegistry, scannerService,
                configManager, queueManager, progressMapper, preloadService);
        PlaybackService playbackService = new PlaybackService(sessionRegistry, playbackEngine, queueManager);
        BroadcastService broadcastService = new BroadcastService(configManager, scannerService, mapper);

        // ===== 启动扫描 + 恢复队列（替代原 @PostConstruct / ApplicationReadyEvent） =====
        scannerService.initScan();
        sessionRegistry.restoreDefaultSessionQueue();

        // ===== 调度器：广播 / 会话巡检 / 曲库重扫（替代原 @Scheduled） =====
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "hellomusic-sched");
            t.setDaemon(true);
            return t;
        });
        long broadcastInterval = configManager.getConfig().getBroadcastInterval() != null
                ? configManager.getConfig().getBroadcastInterval() : 30000L;
        scheduler.scheduleWithFixedDelay(safe(broadcastService::broadcast), 0,
                broadcastInterval, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(safe(sessionRegistry::inspectSessions), 30, 30, TimeUnit.SECONDS);
        long rescanInterval = configManager.getConfig().getRescanInterval() != null
                ? configManager.getConfig().getRescanInterval() : ConfigManager.DEFAULT_RESCAN_INTERVAL;
        scheduler.scheduleWithFixedDelay(safe(scannerService::scanLibrary), rescanInterval,
                rescanInterval, TimeUnit.MILLISECONDS);

        // ===== 控制面（Unix Socket 本机 + HTTP）+ 数据面（HTTP 媒体） =====
        CommandDispatcher dispatcher = new CommandDispatcher(playbackService, playlistService,
                scannerService, broadcastService, configManager, mapper, validator, executor);

        Path socketPath = configManager.getUnixSocketPath();
        UnixSocketServer socketServer = new UnixSocketServer(socketPath, dispatcher, mapper);
        Thread socketThread = new Thread(socketServer::start, "hellomusic-socket");
        socketThread.setDaemon(true);
        socketThread.start();

        HttpGateway httpGateway = new HttpGateway(portOf(configManager), scannerService,
                preloadService, dispatcher, mapper);
        try {
            httpGateway.start();
        } catch (Exception e) {
            System.err.println("⚠️  HTTP 网关启动失败: " + e.getMessage());
        }

        printStartupBanner(socketPath, portOf(configManager),
                configManager.getConfig().getBroadcastPort() != null
                        ? configManager.getConfig().getBroadcastPort() : 9999);

        // ===== 关闭钩子：优雅回收线程与 socket =====
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            socketServer.stop();
            httpGateway.stop();
            scannerService.shutdown();
            executor.shutdownNow();
            scheduler.shutdownNow();
        }, "hellomusic-shutdown"));
    }

    /** 包装定时任务：异常不中断后续调度。 */
    private static Runnable safe(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                System.err.println("⚠️  定时任务出错: " + e.getMessage());
            }
        };
    }

    /** 读取配置 JSON；文件不存在或损坏时返回 {@code null}（全部回退默认）。 */
    private static JsonNode readConfigJson() {
        java.io.File configFile = new java.io.File("hellomusic-config.json");
        if (!configFile.exists()) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(configFile);
        } catch (Exception e) {
            System.err.println("⚠️  读取 hellomusic-config.json 失败，将使用默认配置: " + e.getMessage());
            return null;
        }
    }

    private static int portOf(ConfigManager configManager) {
        Integer port = configManager.getConfig().getServerPort();
        return port != null ? port : 8080;
    }

    /** 是否把运行日志额外写入文件（{@code enableFileLog}=1）。 */
    private static boolean isFileLogEnabled(JsonNode root) {
        return root != null && root.hasNonNull("enableFileLog") && root.get("enableFileLog").asInt() == 1;
    }

    /** 计算本次启动的日志文件全路径：{@code <日志位置>/<日期>-<启动时间>.txt}。 */
    private static Path computeLogFile(JsonNode root) {
        String raw = (root != null && root.hasNonNull("logPath"))
                ? root.get("logPath").asText()
                : ConfigManager.DEFAULT_LOG_PATH;
        Path dir = ConfigManager.expandHome(raw,
                Paths.get(System.getProperty("user.home"), ".local", "share", "HelloMusic", "logs"));
        return dir.resolve(ConfigManager.logFileName(LocalDateTime.now()));
    }

    /** 框内可用宽度（不含左右边框）。 */
    private static final int BANNER_INNER_WIDTH = 66;

    private static void printStartupBanner(Path socketPath, int serverPort, int broadcastPort) {
        String title = "HelloMusic Server Started";
        int pad = (BANNER_INNER_WIDTH - title.length()) / 2;
        System.out.println();
        System.out.println("╔" + "═".repeat(BANNER_INNER_WIDTH) + "╗");
        System.out.println("║" + " ".repeat(pad) + title + " ".repeat(BANNER_INNER_WIDTH - pad - title.length()) + "║");
        System.out.println("║" + " ".repeat(BANNER_INNER_WIDTH) + "║");
        System.out.println(boxLine("  Control (Unix Socket): " + socketPath));
        System.out.println(boxLine("  Control (HTTP): POST http://localhost:" + serverPort + "/control"));
        System.out.println(boxLine("  Media (HTTP): GET http://localhost:" + serverPort + "/stream/{id}"));
        System.out.println(boxLine("  Broadcast: UDP " + broadcastPort));
        System.out.println("╚" + "═".repeat(BANNER_INNER_WIDTH) + "╝");
        System.out.println();
    }

    /** 渲染一行框内内容：超长按字符数截断并补空格，保证右边框始终对齐。 */
    private static String boxLine(String text) {
        if (text.length() > BANNER_INNER_WIDTH) {
            text = text.substring(0, BANNER_INNER_WIDTH - 1) + "…";
        }
        return "║" + text + " ".repeat(BANNER_INNER_WIDTH - text.length()) + "║";
    }
}
