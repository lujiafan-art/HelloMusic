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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖扫描服务的「三阶段」行为：串行收集候选文件 → 串行比指纹决定复用 → 并行解析其余文件。
 *
 * <p>这里用真实临时目录而不是 mock 文件系统——扫描的价值正在于把一堆真实文件变成音乐库，
 * 只有真跑一遍才能验证并行与串行得到同样的结果。文件内容是伪造的，元数据解析会走文件名
 * 兜底，所以断言的是「哪些文件入了库」，而不是解析出来的标签。</p>
 *
 * <p>增量部分用<b>对象身份</b>来验证：复用的判定结果就是「沿用上次那个 MusicFile 实例」，
 * 因此第二次扫描后 {@code assertSame} 成立即说明跳过了重新解析，{@code assertNotSame}
 * 则说明文件被判定为变更、确实重新解析了。这比断言「内容相同」更直接——内容兜底的结果
 * 本来就相同，无法区分是否真的复用了缓存。</p>
 *
 * <p>{@code ConfigManager} 被 mock：它的构造函数会读写当前工作目录下的配置文件，
 * 在单测里实例化会污染项目目录（与 {@code ConfigManagerTest} 的取舍一致）。</p>
 */
class MusicScannerServiceTest {

    @TempDir
    Path libraryDir;

    /** 用于放置「库目录之外」的真实文件，验证符号链接能跨目录收录。 */
    @TempDir
    Path externalDir;

    /** 本用例创建过的扫描器，结束时统一回收各自持有的解析线程池。 */
    private final List<MusicScannerService> created = new ArrayList<>();

    @AfterEach
    void tearDown() {
        created.forEach(MusicScannerService::shutdown);
        created.clear();
    }

    private MusicScannerService newScanner(List<String> libraryPaths, int scanThreads) {
        ConfigManager.Config config = new ConfigManager.Config();
        config.setSupportedExtensions(new ArrayList<>(List.of("mp3", "flac")));
        config.setScanThreads(scanThreads);

        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getConfig()).thenReturn(config);
        // 扫描路径由 ConfigManager 的解析型访问器提供（真实实现会展开 ~；这里直接给绝对路径）
        when(configManager.getMusicPaths()).thenReturn(libraryPaths.stream().map(Path::of).toList());

        MusicScannerService scanner = new MusicScannerService(configManager);
        created.add(scanner);
        return scanner;
    }

    /** 写一个内容无意义的音频文件，必要时自动创建父目录。 */
    private void writeTrack(String relativePath) throws IOException {
        Path target = libraryDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "not a real audio file");
    }

    /** 创建符号链接；当前环境不支持（无权限 / 文件系统限制）时中止用例而非误报失败。 */
    private Path symlink(Path target, Path link) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "当前环境不支持创建符号链接: " + e.getMessage());
            return null; // assumeTrue 已抛出 TestAbortedException，不会执行到这里
        }
    }

    private Set<String> idsOf(MusicScannerService scanner) {
        return scanner.getAllMusic().stream()
                .map(MusicFile::getId)
                .collect(Collectors.toSet());
    }

    /**
     * 按文件名索引当前库。用文件名而不是路径做 key：{@code @TempDir} 的路径里含随机串，
     * 断言里没必要把它拼进来，而文件名在本用例的临时目录内天然唯一。
     */
    private Map<String, MusicFile> byFileName(MusicScannerService scanner) {
        return scanner.getAllMusic().stream()
                .collect(Collectors.toMap(MusicFile::getFileName, Function.identity()));
    }

    @Test
    void scansSupportedFilesRecursivelyAndIgnoresOthers() throws IOException {
        writeTrack("a.mp3");
        writeTrack("b.flac");
        writeTrack("nested/deep/c.mp3");
        writeTrack("readme.txt");
        writeTrack("nested/cover.jpg");

        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 4);
        scanner.scanLibrary();

        assertEquals(3, scanner.getLibrarySize(), "只应收录受支持扩展名的文件，且需递归子目录");
    }

    @Test
    void parallelAndSerialScansProduceTheSameLibrary() throws IOException {
        for (int i = 0; i < 24; i++) {
            writeTrack("album" + (i % 3) + "/track" + i + (i % 2 == 0 ? ".mp3" : ".flac"));
        }

        MusicScannerService parallel = newScanner(List.of(libraryDir.toString()), 4);
        parallel.scanLibrary();
        MusicScannerService serial = newScanner(List.of(libraryDir.toString()), 1);
        serial.scanLibrary();

        assertEquals(24, parallel.getLibrarySize());
        assertEquals(24, serial.getLibrarySize());
        assertEquals(idsOf(serial), idsOf(parallel), "并行解析不应改变收录结果");
    }

    @Test
    void invalidLibraryPathsAreSkippedWithoutFailingTheScan() throws IOException {
        writeTrack("only.mp3");

        MusicScannerService scanner = newScanner(
                List.of(libraryDir.resolve("does-not-exist").toString(), libraryDir.toString()), 2);
        scanner.scanLibrary();

        assertEquals(1, scanner.getLibrarySize(), "不存在的目录应被跳过，其余目录照常扫描");
    }

    @Test
    void overlappingLibraryDirsDoNotDuplicateTracks() throws IOException {
        // 父目录 + 其子目录同时配置：同一文件会被 collectCandidates 收集两次，
        // planScan 应以规范化路径去重，只解析一次。
        writeTrack("root.mp3");
        writeTrack("sub/nested.mp3");

        MusicScannerService scanner = newScanner(
                List.of(libraryDir.toString(), libraryDir.resolve("sub").toString()), 4);
        scanner.scanLibrary();

        assertEquals(2, scanner.getLibrarySize(), "重叠目录不应让同一文件重复入库");
        assertEquals(Set.of("root.mp3", "nested.mp3"), byFileName(scanner).keySet());
    }

    @Test
    void metadataFallsBackToFileName() throws IOException {
        writeTrack("周杰伦 - 稻香.mp3");

        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 2);
        scanner.scanLibrary();

        MusicFile musicFile = scanner.getAllMusic().get(0);
        assertNotNull(musicFile.getMetadata(), "解析失败也应给出兜底元数据");
        assertEquals("周杰伦", musicFile.getMetadata().getArtist());
        assertEquals("稻香", musicFile.getMetadata().getTitle());
        assertTrue(musicFile.getUrl().startsWith("/stream/"), "入库时应带上流式播放地址");
    }

    @Test
    void emptyLibraryDirectoryYieldsEmptyLibrary() {
        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 4);
        scanner.scanLibrary();

        assertEquals(0, scanner.getLibrarySize());
        assertTrue(scanner.getAllMusic().isEmpty());
    }

    @Test
    void unchangedFilesAreReusedInsteadOfBeingReparsed() throws IOException {
        writeTrack("a.mp3");
        writeTrack("album/b.flac");

        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 4);
        scanner.scanLibrary();
        Map<String, MusicFile> first = byFileName(scanner);
        assertEquals(2, first.size());

        scanner.scanLibrary();
        Map<String, MusicFile> second = byFileName(scanner);

        assertEquals(first.keySet(), second.keySet(), "重扫不应改变收录的文件");
        first.forEach((name, file) -> assertSame(file, second.get(name),
                name + " 未变更，应复用上次解析出来的对象（即跳过重新解析）"));
    }

    @Test
    void changedFilesAreReparsedAndDeletedFilesDropOut() throws IOException {
        writeTrack("byContent.mp3");
        writeTrack("byDelete.mp3");

        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 4);
        scanner.scanLibrary();
        Map<String, MusicFile> first = byFileName(scanner);
        assertEquals(2, first.size());

        // 内容变化（大小随之变化）→ 应重新解析
        Files.writeString(libraryDir.resolve("byContent.mp3"),
                "a completely different body, deliberately longer than the original");
        // 被删除 → 应从库中消失
        Files.delete(libraryDir.resolve("byDelete.mp3"));

        scanner.scanLibrary();
        Map<String, MusicFile> second = byFileName(scanner);

        assertEquals(1, second.size(), "被删除的文件不应再出现在库里");
        assertNotSame(first.get("byContent.mp3"), second.get("byContent.mp3"), "内容变化应触发重新解析");
        assertEquals(Files.size(libraryDir.resolve("byContent.mp3")),
                second.get("byContent.mp3").getFileSize(), "重新解析后的记录应带上最新的文件大小");
    }

    @Test
    void mtimeChangeAloneTriggersReparse() throws IOException {
        // 有些受限环境（容器 / 沙箱）会以 EPERM 拦截 utimensat，无法修改文件时间。
        // 此时跳过本用例：mtime 检测逻辑的其余覆盖由上面 changedFiles... 用例兜底。
        assumeTrue(canSetMtime(), "当前环境不允许修改文件时间，跳过 mtime 变更检测");

        writeTrack("byMtime.flac");

        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 4);
        scanner.scanLibrary();
        MusicFile first = byFileName(scanner).get("byMtime.flac");

        // 只动修改时间、大小不变 → 同样应重新解析
        Path byMtime = libraryDir.resolve("byMtime.flac");
        Files.setLastModifiedTime(byMtime,
                FileTime.fromMillis(Files.getLastModifiedTime(byMtime).toMillis() + 10_000L));

        scanner.scanLibrary();
        MusicFile second = byFileName(scanner).get("byMtime.flac");

        assertNotSame(first, second, "mtime 变化（大小不变）应触发重新解析");
        assertTrue(second.getLastModified().isAfter(first.getLastModified()),
                "重新解析后的记录应带上最新的修改时间");
    }

    /** 探测当前环境能否修改文件时间（{@code Files.setLastModifiedTime}）。 */
    private static boolean canSetMtime() {
        try {
            Path probe = Files.createTempFile("hellomusic-mtime-probe", ".tmp");
            try {
                FileTime now = Files.getLastModifiedTime(probe);
                Files.setLastModifiedTime(probe, FileTime.fromMillis(now.toMillis() + 1_000L));
                return true;
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void filesAddedAfterAFullScanArePickedUpByTheNextScan() throws IOException {
        writeTrack("existing.mp3");

        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 2);
        scanner.scanLibrary();
        assertEquals(1, scanner.getLibrarySize());

        writeTrack("added-later.flac");
        writeTrack("added-later-too.MP3");

        scanner.scanLibrary();

        assertEquals(3, scanner.getLibrarySize(), "重扫应收录新增文件（扩展名大小写不敏感）");
        assertTrue(byFileName(scanner).keySet().containsAll(
                Set.of("existing.mp3", "added-later.flac", "added-later-too.MP3")));
    }

    @Test
    void symlinkedDirectoryContentsAreScanned() throws IOException {
        // 真实文件在库目录之外的另一个临时目录，用目录符号链接把它接入库目录
        Files.writeString(externalDir.resolve("outside.mp3"), "not a real audio file");
        symlink(externalDir, libraryDir.resolve("linked-dir"));

        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 2);
        scanner.scanLibrary();

        assertTrue(byFileName(scanner).keySet().contains("outside.mp3"),
                "符号链接目录内的音乐文件应被扫描收录");
    }

    @Test
    void libraryPathItselfBeingASymlinkToADirectoryIsScanned() throws IOException {
        // musicPaths 里配置的路径本身就是一个指向目录的符号链接（如 ~/Music -> /mnt/data）
        Files.writeString(externalDir.resolve("song.mp3"), "not a real audio file");
        Path link = symlink(externalDir, libraryDir.resolve("music-link"));

        MusicScannerService scanner = newScanner(List.of(link.toString()), 2);
        scanner.scanLibrary();

        assertEquals(1, scanner.getLibrarySize(), "音乐库路径本身是目录符号链接时应正常扫描");
        assertTrue(byFileName(scanner).keySet().contains("song.mp3"));
    }

    @Test
    void symlinkToAnIndividualMusicFileIsScanned() throws IOException {
        Files.writeString(externalDir.resolve("song.mp3"), "not a real audio file");
        symlink(externalDir.resolve("song.mp3"), libraryDir.resolve("linked-song.mp3"));

        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 2);
        scanner.scanLibrary();

        assertEquals(1, scanner.getLibrarySize(), "指向单个文件的符号链接应被收录");
    }

    @Test
    void cyclicSymlinkDoesNotAbortTheWholeScan() throws IOException {
        writeTrack("safe.mp3");
        symlink(libraryDir, libraryDir.resolve("loop")); // 子目录链回库目录本身 → 循环

        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 2);
        scanner.scanLibrary();

        assertTrue(byFileName(scanner).keySet().contains("safe.mp3"),
                "循环符号链接不应中断整次扫描，其余文件照常收录");
    }

    @Test
    void manualRescanWhileScanningIsQueuedThenRunsAfter() throws Exception {
        // 用可阻塞的 ConfigManager 精确控制「扫描进行中」：第一次 getMusicPaths() 阻塞在
        // latch 上，模拟一次耗时扫描尚未结束；此时手动重扫应被排队而非丢弃。
        writeTrack("existing.mp3");

        CountDownLatch firstScanEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstScan = new CountDownLatch(1);
        AtomicInteger musicPathsCalls = new AtomicInteger();

        ConfigManager.Config config = new ConfigManager.Config();
        config.setSupportedExtensions(new ArrayList<>(List.of("mp3")));
        config.setScanThreads(1);

        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getConfig()).thenReturn(config);
        when(configManager.getMusicPaths()).thenAnswer(inv -> {
            if (musicPathsCalls.incrementAndGet() == 1) {
                firstScanEntered.countDown();
                releaseFirstScan.await(5, TimeUnit.SECONDS);
            }
            return List.of(libraryDir);
        });

        MusicScannerService scanner = new MusicScannerService(configManager);
        created.add(scanner);

        // 线程 1：第一次扫描，阻塞在 getMusicPaths() 上
        Thread first = new Thread(scanner::scanLibrary, "scan-1");
        first.start();
        assertTrue(firstScanEntered.await(5, TimeUnit.SECONDS), "第一次扫描应已进入");
        assertTrue(scanner.isScanning(), "第一次扫描进行中，isScanning 应为 true");

        // 主线程：此时已有扫描在跑，手动重扫应返回 false（排队），而不是被静默丢弃
        boolean startedNow = scanner.scanLibrary();
        assertFalse(startedNow, "已有扫描在跑时，手动重扫应排队而非立即开始");

        // 放行第一次扫描；排队的重扫应在同一次调用内自动补跑（getMusicPaths 被再次调用）
        releaseFirstScan.countDown();
        first.join(10_000);

        assertTrue(musicPathsCalls.get() >= 2,
                "手动重扫请求不应被丢弃：第一次扫描结束后应补跑一次（getMusicPaths 被再次调用）");
        assertFalse(scanner.isScanning(), "补跑结束后应复位扫描标志");
    }
}
