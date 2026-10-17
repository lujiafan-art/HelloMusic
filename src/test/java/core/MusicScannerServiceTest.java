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
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** 本用例创建过的扫描器，结束时统一回收各自持有的解析线程池。 */
    private final List<MusicScannerService> created = new ArrayList<>();

    @AfterEach
    void tearDown() {
        created.forEach(MusicScannerService::shutdown);
        created.clear();
    }

    private MusicScannerService newScanner(List<String> libraryPaths, int scanThreads) {
        ConfigManager.Config config = new ConfigManager.Config();
        config.setMusicLibraryPaths(new ArrayList<>(libraryPaths));
        config.setSupportedExtensions(new ArrayList<>(List.of("mp3", "flac")));
        config.setScanThreads(scanThreads);

        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getConfig()).thenReturn(config);

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
    void metadataFallsBackToFileName() throws IOException {
        writeTrack("周杰伦 - 稻香.mp3");

        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 2);
        scanner.scanLibrary();

        MusicFile musicFile = scanner.getAllMusic().get(0);
        assertNotNull(musicFile.getMetadata(), "解析失败也应给出兜底元数据");
        assertEquals("周杰伦", musicFile.getMetadata().getArtist());
        assertEquals("稻香", musicFile.getMetadata().getTitle());
        assertTrue(musicFile.getUrl().startsWith("/api/stream/"), "入库时应带上流式播放地址");
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
        writeTrack("byMtime.flac");
        writeTrack("byDelete.mp3");

        MusicScannerService scanner = newScanner(List.of(libraryDir.toString()), 4);
        scanner.scanLibrary();
        Map<String, MusicFile> first = byFileName(scanner);
        assertEquals(3, first.size());

        // 内容变化（大小随之变化）→ 应重新解析
        Files.writeString(libraryDir.resolve("byContent.mp3"),
                "a completely different body, deliberately longer than the original");
        // 只动修改时间、大小不变 → 同样应重新解析
        Path byMtime = libraryDir.resolve("byMtime.flac");
        Files.setLastModifiedTime(byMtime,
                FileTime.fromMillis(Files.getLastModifiedTime(byMtime).toMillis() + 10_000L));
        // 被删除 → 应从库中消失
        Files.delete(libraryDir.resolve("byDelete.mp3"));

        scanner.scanLibrary();
        Map<String, MusicFile> second = byFileName(scanner);

        assertEquals(2, second.size(), "被删除的文件不应再出现在库里");
        assertNotSame(first.get("byContent.mp3"), second.get("byContent.mp3"), "内容变化应触发重新解析");
        assertNotSame(first.get("byMtime.flac"), second.get("byMtime.flac"), "mtime 变化应触发重新解析");
        assertEquals(Files.size(libraryDir.resolve("byContent.mp3")),
                second.get("byContent.mp3").getFileSize(), "重新解析后的记录应带上最新的文件大小");
        assertTrue(second.get("byMtime.flac").getLastModified()
                        .isAfter(first.get("byMtime.flac").getLastModified()),
                "重新解析后的记录应带上最新的修改时间");
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
}
