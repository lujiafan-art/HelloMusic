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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.FileAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把运行日志额外写入文件（在控制台输出之外「追加」，而非「替代」）。
 *
 * <p>是否启用由 {@code hellomusic-config.json} 里的 {@code enableFileLog} 决定，
 * 调用方在 Spring 启动<b>之前</b>据此判断并传入已解析好的日志文件全路径，
 * 这样连 Spring 自身的启动日志也会被记录进去。目录不存在会自动创建。</p>
 */
final class LoggingInstaller {

    private static final Logger log = LoggerFactory.getLogger(LoggingInstaller.class);

    /** 文件日志的行格式，与控制台一致但不带 ANSI 颜色。 */
    private static final String FILE_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";

    private LoggingInstaller() {
    }

    /**
     * 为 logback 挂载文件 appender，写入 {@code logFile}（形如
     * {@code <日志位置>/<日期>-<启动时间>.txt}）。应在 Spring 启动<b>之前</b>调用。
     */
    static void install(Path logFile) {
        try {
            Path dir = logFile.getParent();
            if (dir != null) {
                Files.createDirectories(dir); // 自动创建日志目录
            }
            attachFileAppender(logFile);
            log.info("日志已启用，输出文件: " + logFile);
        } catch (IOException | RuntimeException e) {
            // 文件日志是附加能力，失败不能影响服务启动
            System.err.println("⚠️  启用文件日志失败，继续仅输出到控制台: " + e.getMessage());
        }
    }

    private static void attachFileAppender(Path logFile) {
        org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return; // 不是 Logback（例如换成了其它 SLF4J 实现），静默跳过
        }
        ch.qos.logback.classic.Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        // 幂等保护：重复 install（如测试或重启逻辑）不应重复挂载，否则同一日志会输出多份
        if (root.getAppender("hellomusic-file") != null) {
            return;
        }

        // 复用 root 的级别决策：若未显式设置（null），退回 INFO
        Level level = root.getLevel() != null ? root.getLevel() : Level.INFO;

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(FILE_PATTERN);
        encoder.start();

        FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> fileAppender = new FileAppender<>();
        fileAppender.setContext(context);
        fileAppender.setName("hellomusic-file");
        fileAppender.setFile(logFile.toString());
        fileAppender.setAppend(true);   // 追加，不覆盖已有日志
        fileAppender.setEncoder(encoder);
        fileAppender.start();

        root.addAppender(fileAppender);
        root.setLevel(level);
    }
}