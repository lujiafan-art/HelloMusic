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

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@code config.set} 命令的两个行为保证：局部更新（合并语义）与字段校验。
 *
 * <p>这里刻意不走 Spring 容器：{@code ConfigManager} 的构造函数会读写当前工作目录下的
 * {@code hellomusic-config.json}，在单测里实例化会污染项目目录。被验证的
 * {@link ConfigManager#mergeInto} 与 {@link ConfigManager.Config} 上的约束都是纯逻辑。
 */
class ConfigManagerTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    /** 一份完整且合法的配置，作为「服务器上已有的配置」。 */
    private static ConfigManager.Config existingConfig() {
        ConfigManager.Config config = new ConfigManager.Config();
        config.setServerPort(8080);
        config.setBroadcastPort(9999);
        config.setBroadcastInterval(30000);
        config.setScanThreads(4);
        config.setRescanInterval(300000);
        config.setSupportedExtensions(new ArrayList<>(List.of("mp3", "flac")));
        config.setServerName("HelloMusic");
        config.setLastPlayMode("FORWARD");
        // 用户选项（原 UserConfig 的 7 项迁入）
        config.setMaxQueueSize(500);
        config.setAutoSaveQueue(1);
        config.setQueueSavePath("/data/queue");
        config.setMusicPaths(new ArrayList<>(List.of("~/Music", "/mnt/nas")));
        config.setEnableFileLog(1);
        config.setLogPath("/var/log/hellomusic");
        config.setEnableHeartbeat(0);
        config.setUnixSocketPath("/run/hellomusic.sock");
        return config;
    }

    private static ConfigManager.Config emptyPatch() {
        return new ConfigManager.Config();
    }

    private static Set<ConstraintViolation<ConfigManager.Config>> validate(ConfigManager.Config config) {
        return VALIDATOR.validate(config);
    }

    private static boolean violates(Set<ConstraintViolation<ConfigManager.Config>> violations, String property) {
        return violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().startsWith(property));
    }

    // ========== 合并语义 ==========

    @Test
    void omittedFieldsKeepTheirCurrentValues() {
        ConfigManager.Config patch = emptyPatch();
        patch.setServerName("客厅音乐");

        ConfigManager.Config merged = ConfigManager.mergeInto(existingConfig(), patch);

        assertEquals("客厅音乐", merged.getServerName(), "显式提供的字段应生效");
        assertEquals(Integer.valueOf(8080), merged.getServerPort(), "未提交的端口应保留原值");
        assertEquals(Integer.valueOf(9999), merged.getBroadcastPort());
        assertEquals(Integer.valueOf(30000), merged.getBroadcastInterval());
        assertEquals(Integer.valueOf(4), merged.getScanThreads(), "未提交的解析线程数应保留原值");
        assertEquals(Integer.valueOf(300000), merged.getRescanInterval(), "未提交的重扫间隔应保留原值");
        assertEquals(List.of("mp3", "flac"), merged.getSupportedExtensions());
        assertEquals("FORWARD", merged.getLastPlayMode());
        assertEquals(Integer.valueOf(500), merged.getMaxQueueSize(), "未提交的用户选项应保留原值");
        assertEquals(Integer.valueOf(1), merged.getAutoSaveQueue());
        assertEquals(List.of("~/Music", "/mnt/nas"), merged.getMusicPaths());
        assertEquals(Integer.valueOf(0), merged.getEnableHeartbeat());
        assertEquals("/run/hellomusic.sock", merged.getUnixSocketPath(), "未提交的 Unix Socket 路径应保留原值");
    }

    @Test
    void providedFieldsOverrideCurrentValues() {
        ConfigManager.Config patch = emptyPatch();
        patch.setServerPort(9090);
        patch.setBroadcastPort(9998);
        patch.setBroadcastInterval(60000);
        patch.setScanThreads(2);
        patch.setSupportedExtensions(new ArrayList<>(List.of("mp3")));
        patch.setServerName("NAS");
        patch.setLastPlayMode("SHUFFLE");

        ConfigManager.Config merged = ConfigManager.mergeInto(existingConfig(), patch);

        assertEquals(Integer.valueOf(9090), merged.getServerPort());
        assertEquals(Integer.valueOf(9998), merged.getBroadcastPort());
        assertEquals(Integer.valueOf(60000), merged.getBroadcastInterval());
        assertEquals(Integer.valueOf(2), merged.getScanThreads());
        assertEquals(List.of("mp3"), merged.getSupportedExtensions());
        assertEquals("NAS", merged.getServerName());
        assertEquals("SHUFFLE", merged.getLastPlayMode());
    }

    @Test
    void userOptionFieldsOverrideCurrentValues() {
        ConfigManager.Config patch = emptyPatch();
        patch.setMaxQueueSize(50);
        patch.setAutoSaveQueue(0);
        patch.setQueueSavePath("/tmp/q");
        patch.setMusicPaths(new ArrayList<>(List.of("/srv/music")));
        patch.setEnableFileLog(0);
        patch.setLogPath("/tmp/logs");
        patch.setEnableHeartbeat(1);
        patch.setUnixSocketPath("/tmp/hello.sock");

        ConfigManager.Config merged = ConfigManager.mergeInto(existingConfig(), patch);

        assertEquals(Integer.valueOf(50), merged.getMaxQueueSize());
        assertEquals(Integer.valueOf(0), merged.getAutoSaveQueue());
        assertEquals("/tmp/q", merged.getQueueSavePath());
        assertEquals(List.of("/srv/music"), merged.getMusicPaths());
        assertEquals(Integer.valueOf(0), merged.getEnableFileLog());
        assertEquals("/tmp/logs", merged.getLogPath());
        assertEquals(Integer.valueOf(1), merged.getEnableHeartbeat());
        assertEquals("/tmp/hello.sock", merged.getUnixSocketPath());
    }

    @Test
    void mergeDoesNotMutateTheBaseConfig() {
        ConfigManager.Config base = existingConfig();

        ConfigManager.Config merged = ConfigManager.mergeInto(base, emptyPatch());

        assertNotSame(base, merged, "合并结果应是新对象，避免调用方误改到共享实例");
        assertEquals("HelloMusic", base.getServerName());
        assertSame(base, ConfigManager.mergeInto(base, null), "patch 为 null 时原样返回");
    }

    // ========== 校验：允许局部更新 ==========

    @Test
    void partialPatchIsValidSoOmittedFieldsAreNotRejected() {
        ConfigManager.Config patch = emptyPatch();
        patch.setServerPort(9090);

        assertTrue(validate(patch).isEmpty(),
                "未提交的字段是 null，必须视为「不改这一项」而不是校验失败");
    }

    @Test
    void fullValidConfigPassesValidation() {
        assertTrue(validate(existingConfig()).isEmpty());
    }

    // ========== 校验：非法取值被拦下 ==========

    @Test
    void rejectsOutOfRangePorts() {
        ConfigManager.Config tooLow = emptyPatch();
        tooLow.setServerPort(0);
        ConfigManager.Config tooHigh = emptyPatch();
        tooHigh.setBroadcastPort(70000);

        assertTrue(violates(validate(tooLow), "serverPort"));
        assertTrue(violates(validate(tooHigh), "broadcastPort"));
    }

    @Test
    void rejectsTooShortBroadcastInterval() {
        ConfigManager.Config patch = emptyPatch();
        patch.setBroadcastInterval(500);

        assertTrue(violates(validate(patch), "broadcastInterval"));
    }

    @Test
    void rejectsOutOfRangeScanThreads() {
        ConfigManager.Config tooLow = emptyPatch();
        tooLow.setScanThreads(0);
        ConfigManager.Config tooHigh = emptyPatch();
        tooHigh.setScanThreads(1000);

        assertTrue(violates(validate(tooLow), "scanThreads"), "0 个解析线程毫无意义，应被拦下");
        assertTrue(violates(validate(tooHigh), "scanThreads"), "线程数上限为 64");
    }

    @Test
    void rejectsOutOfRangeRescanInterval() {
        ConfigManager.Config tooShort = emptyPatch();
        tooShort.setRescanInterval(5000);
        ConfigManager.Config tooLong = emptyPatch();
        tooLong.setRescanInterval(86_400_001);

        assertTrue(violates(validate(tooShort), "rescanInterval"), "重扫间隔下限为 10 秒");
        assertTrue(violates(validate(tooLong), "rescanInterval"), "重扫间隔上限为 24 小时");
    }

    @Test
    void rejectsOutOfRangeMaxQueueSize() {
        ConfigManager.Config tooLow = emptyPatch();
        tooLow.setMaxQueueSize(0);
        ConfigManager.Config tooHigh = emptyPatch();
        tooHigh.setMaxQueueSize(100001);

        assertTrue(violates(validate(tooLow), "maxQueueSize"), "队列上限至少为 1");
        assertTrue(violates(validate(tooHigh), "maxQueueSize"), "队列上限最多 100000");
    }

    @Test
    void rejectsNonBinarySwitches() {
        ConfigManager.Config autoSave = emptyPatch();
        autoSave.setAutoSaveQueue(2);
        ConfigManager.Config fileLog = emptyPatch();
        fileLog.setEnableFileLog(7);
        ConfigManager.Config heartbeat = emptyPatch();
        heartbeat.setEnableHeartbeat(-1);

        assertTrue(violates(validate(autoSave), "autoSaveQueue"));
        assertTrue(violates(validate(fileLog), "enableFileLog"));
        assertTrue(violates(validate(heartbeat), "enableHeartbeat"));
    }

    @Test
    void rejectsEmptyMusicPaths() {
        ConfigManager.Config emptyPaths = emptyPatch();
        emptyPaths.setMusicPaths(new ArrayList<>());
        ConfigManager.Config blankElement = emptyPatch();
        blankElement.setMusicPaths(new ArrayList<>(List.of("~/Music", "  ")));

        assertTrue(violates(validate(emptyPaths), "musicPaths"));
        assertTrue(violates(validate(blankElement), "musicPaths"), "音乐目录里的空白字符串应被拦下");
    }

    @Test
    void rejectsEmptyCollectionsAndBlankElements() {
        ConfigManager.Config emptyExtensions = emptyPatch();
        emptyExtensions.setSupportedExtensions(new ArrayList<>());
        ConfigManager.Config blankExtension = emptyPatch();
        blankExtension.setSupportedExtensions(new ArrayList<>(List.of("mp3", "  ")));

        assertTrue(violates(validate(emptyExtensions), "supportedExtensions"));
        assertTrue(violates(validate(blankExtension), "supportedExtensions"),
                "扩展名里的空白字符串应被拦下");
    }

    @Test
    void rejectsBlankServerName() {
        ConfigManager.Config patch = emptyPatch();
        patch.setServerName("   ");

        assertTrue(violates(validate(patch), "serverName"));
    }

    @Test
    void rejectsBlankUnixSocketPath() {
        ConfigManager.Config patch = emptyPatch();
        patch.setUnixSocketPath("   ");

        assertTrue(violates(validate(patch), "unixSocketPath"));
    }

    @Test
    void rejectsUnknownPlayModeButAcceptsAnyCase() {
        ConfigManager.Config bad = emptyPatch();
        bad.setLastPlayMode("BACKWARD");
        ConfigManager.Config lowerCase = emptyPatch();
        lowerCase.setLastPlayMode("shuffle");

        assertTrue(violates(validate(bad), "lastPlayMode"));
        assertTrue(validate(lowerCase).isEmpty(), "播放模式应忽略大小写");
    }

    // ========== 播放队列持久化字段 ==========

    @Test
    void playbackQueueFieldsMergeLikeOtherFields() {
        ConfigManager.Config base = existingConfig();
        base.setPlaybackQueue(new ArrayList<>(List.of("x", "y")));
        base.setCurrentIndex(1);

        // 显式提供时覆盖
        ConfigManager.Config patch = emptyPatch();
        patch.setPlaybackQueue(new ArrayList<>(List.of("a", "b", "c")));
        patch.setCurrentIndex(2);
        ConfigManager.Config merged = ConfigManager.mergeInto(base, patch);
        assertEquals(List.of("a", "b", "c"), merged.getPlaybackQueue());
        assertEquals(Integer.valueOf(2), merged.getCurrentIndex());

        // 未提供时保留旧值（持久化队列不与其它字段更新互相覆盖）
        ConfigManager.Config mergedKeep = ConfigManager.mergeInto(base, emptyPatch());
        assertEquals(List.of("x", "y"), mergedKeep.getPlaybackQueue());
        assertEquals(Integer.valueOf(1), mergedKeep.getCurrentIndex());
    }
}
