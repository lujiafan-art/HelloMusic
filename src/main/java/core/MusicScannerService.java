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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class MusicScannerService {
    /**
     * 并行解析的切块大小。
     *
     * <p>解析任务按此大小切片后提交：块内串行、块间并行。块太小会放大任务调度开销，
     * 太大则会在「文件数不能被线程数整除」时让部分线程提前空闲。</p>
     */
    private static final int PARSE_CHUNK_SIZE = 64;

    // volatile 引用：扫描完成后一次性原子换引用，避免 clear()+putAll() 之间的空库窗口
    private volatile Map<String, MusicFile> musicLibrary = new ConcurrentHashMap<>();

    /**
     * 增量扫描缓存：绝对路径 → 上次成功解析时的「指纹 + 结果」。
     *
     * <p>与 {@link #musicLibrary} 在同一次扫描结束时一起换引用，因此读者看到的库与缓存
     * 永远是同一批次的一致快照，不会出现「库已更新、缓存还是老的」这种错配。</p>
     */
    private volatile Map<String, CachedTrack> trackCache = Map.of();

    private final ConfigManager configManager;
    // 原子标志：多个触发源（启动 / @Scheduled / REST /scan / CLI scan）可能并发调用
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile long lastScanTime = 0;

    /**
     * 元数据解析专用线程池。
     *
     * <p>与 Web 请求线程池隔离：扫描是长任务，混用会占满 Tomcat 的线程而拖慢接口响应。
     * 池按配置的线程数惰性创建，配置改动后下一次扫描自动重建。</p>
     */
    private final Object parseExecutorLock = new Object();
    private ExecutorService parseExecutor;
    private int parseExecutorThreads;

    @Autowired
    public MusicScannerService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 应用启动时同步执行一次初始扫描。
     * 放在 @PostConstruct 而不是构造器里：此时依赖已注入、Bean 已构建完毕，
     * 既避免在构造期间做重活，也保证启动后音乐库立刻可用。
     * 后续刷新交给下面的 @Scheduled 定时任务。
     */
    @PostConstruct
    public void initScan() {
        scanLibrary();
    }

    /**
     * 扫描音乐库：目录遍历与指纹比对串行、元数据解析并行，最后一次性换入新库。
     *
     * <p>由启动（{@link #initScan()}）、定时任务、REST {@code /api/scan} 与 CLI 共同触发，
     * 因此入口处用 CAS 保证同一时刻只有一次扫描在跑。</p>
     *
     * <p><b>增量</b>：只有「本次新出现」或「自上次成功解析以来 path+size+mtime 变过」的文件
     * 才会真正去解析元数据，其余文件直接复用上次的结果。缓存只活在内存里、随进程消失，
     * 因此不存在缓存过期或损坏的问题；首次扫描（冷启动）必然是全量解析。</p>
     */
    @Scheduled(initialDelay = 300000, fixedDelay = 300000)
    public void scanLibrary() {
        // CAS 抢占：只有一个线程能把 false 翻转为 true，其余直接返回
        if (!scanning.compareAndSet(false, true)) {
            System.out.println("⏭ 已有扫描任务在执行，跳过本次触发");
            return;
        }

        try {
            System.out.println("🔍 Starting music library scan...");
            long startTime = System.currentTimeMillis();

            ConfigManager.Config config = configManager.getConfig();
            int threads = resolveThreadCount(config);

            // 三阶段：① 串行收集候选文件（遍历目录，只做过滤）② 串行 stat 挑出没变过的
            // ③ 并行解析其余文件（很重：要打开文件读标签甚至音频头）
            List<Path> candidates = collectCandidates(
                    config.getMusicLibraryPaths(), config.getSupportedExtensions());
            ScanPlan plan = planScan(candidates);
            Map<String, MusicFile> refreshed = parseMetadata(plan.pending(), threads);
            if (refreshed == null) {
                // 解析未完整完成（被中断或出现非预期错误），保留原库不动
                System.err.println("⚠️  本次扫描未完成，保留原有音乐库（" + musicLibrary.size() + " 首）");
                return;
            }

            Map<String, MusicFile> newLibrary = new ConcurrentHashMap<>();
            Map<String, CachedTrack> newCache = new ConcurrentHashMap<>();

            // 复用：直接沿用上次解析出来的对象，完全跳过「打开文件 + 读标签」
            for (Map.Entry<String, CachedTrack> entry : plan.reusable().entrySet()) {
                MusicFile file = entry.getValue().file();
                newLibrary.put(file.getId(), file);
                newCache.put(entry.getKey(), entry.getValue());
            }
            // 新增 / 变更：本次解析出来的结果；构建方式保证已删除的文件自然从库中消失
            for (Map.Entry<String, MusicFile> entry : refreshed.entrySet()) {
                MusicFile file = entry.getValue();
                FileStamp stamp = plan.stamps().get(entry.getKey());
                if (stamp == null) {
                    continue;
                }
                newLibrary.put(file.getId(), file);
                newCache.put(entry.getKey(), new CachedTrack(stamp, file));
            }

            // 一次性原子换引用：库与缓存同时更新，读者要么看到旧的一整套、要么看到新的一整套
            musicLibrary = newLibrary;
            trackCache = newCache;
            lastScanTime = System.currentTimeMillis();

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            // 「重解析 + 复用」即本次候选文件总数；解析失败的文件不入库也不入缓存，下次还会重试
            System.out.println("✅ Scan complete. Found " + musicLibrary.size() + " music files in "
                    + duration + "s (重解析 " + plan.pending().size() + " 个, 复用 "
                    + plan.reusable().size() + " 个, " + threads + " 个解析线程)");
        } catch (Exception e) {
            System.err.println("❌ Error scanning library: " + e.getMessage());
        } finally {
            scanning.set(false);
        }
    }

    /**
     * 串行遍历所有音乐库目录，收集候选文件路径。
     *
     * <p>目录遍历只做 stat、开销远小于元数据解析，因此保持串行：一是不必为它引入并发复杂度，
     * 二是 {@code Files.walk} 的底层 DirectoryStream 不支持并发切分，强行并行会抛
     * {@code ClosedDirectoryStreamException} 之类的异常。</p>
     */
    private List<Path> collectCandidates(List<String> libraryPaths, List<String> extensions) {
        List<Path> candidates = new ArrayList<>();
        for (String pathStr : libraryPaths) {
            Path path = Paths.get(pathStr);
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                System.err.println("⚠️  Invalid library path: " + pathStr);
                continue;
            }
            try (var stream = Files.walk(path)) {
                stream.filter(Files::isRegularFile)
                        .filter(file -> isSupportedExtension(file, extensions))
                        .forEach(candidates::add);
            } catch (Exception e) {
                System.err.println("❌ Error scanning directory: " + path + " - " + e.getMessage());
            }
        }
        return candidates;
    }

    /**
     * 串行比对外层文件系统的指纹，决定每个候选文件是「复用上次结果」还是「需要重新解析」。
     *
     * <p>只读 size 与 mtime，不碰文件内容，所以即使库很大，这一步的开销也远小于元数据解析——
     * 增量扫描的收益正来自这里：把「一次 stat」换成「省掉一次完整解析」。判定用 size + mtime
     * 组合，能覆盖绝大多数「改了内容」「整个文件被替换」的情况。</p>
     *
     * <p>极端场景下会漏判：文件在同一 mtime 粒度内被改写且大小恰好不变（某些文件系统上 mtime
     * 精度为秒）。此时旧结果会被继续沿用，直到文件再次变化。音乐库以整文件替换为主，可以接受。</p>
     */
    private ScanPlan planScan(List<Path> candidates) {
        Map<String, CachedTrack> previous = trackCache;
        Map<String, CachedTrack> reusable = new HashMap<>();
        Map<String, FileStamp> stamps = new HashMap<>();
        List<PendingTrack> pending = new ArrayList<>();

        for (Path candidate : candidates) {
            // key 必须与 createMusicFile 里生成 ID 用的 stablePath() 完全一致（均为规范化路径）
            String absolutePath = stablePath(candidate.toFile());
            FileStamp stamp;
            try {
                stamp = FileStamp.read(candidate);
            } catch (IOException | RuntimeException e) {
                // 文件在遍历与 stat 之间被删除、或没有读取权限：跳过该文件，不影响整次扫描
                continue;
            }
            stamps.put(absolutePath, stamp);

            CachedTrack cached = previous.get(absolutePath);
            if (cached != null && cached.stamp().equals(stamp)) {
                reusable.put(absolutePath, cached);
            } else {
                // 新文件、变更过的文件，以及上次解析失败因而不在缓存里的文件，都会走到这里
                pending.add(new PendingTrack(candidate, stamp));
            }
        }
        return new ScanPlan(pending, reusable, stamps);
    }

    /**
     * 并行解析候选文件的元数据，返回「绝对路径 → 解析结果」。
     *
     * <p>解析是整次扫描中最重的一步——每个文件都要被打开、读取标签甚至音频头，属于典型的
     * I/O + CPU 混合负载，文件之间彼此独立，非常适合并行。这里按 {@link #PARSE_CHUNK_SIZE}
     * 切块提交到专用线程池：块内串行、块间并行，既收敛任务数量，又保持负载均衡。</p>
     *
     * <p>方法返回时保证所有任务都已结束（{@code invokeAll} 天然形成屏障），因此返回值可以
     * 安全地被整体换入——不会出现「换引用时还有人往里写」的竞态。</p>
     *
     * @param pending 需要解析的文件及其指纹
     * @param threads 解析线程数；{@code <= 1} 时退化为串行
     * @return 解析结果（只含成功项，按绝对路径索引）；若解析被中断或出现非预期错误则返回
     *         {@code null}，调用方应保留原库
     */
    private Map<String, MusicFile> parseMetadata(List<PendingTrack> pending, int threads) {
        Map<String, MusicFile> parsed = new ConcurrentHashMap<>();
        if (pending.isEmpty()) {
            return parsed;
        }
        if (threads <= 1 || pending.size() == 1) {
            pending.forEach(track -> addToLibrary(track, parsed));
            return parsed;
        }

        // 切块：保证块数不超过线程数，避免大量小任务把调度开销放大
        int chunkSize = Math.max(1, Math.min(PARSE_CHUNK_SIZE,
                (pending.size() + threads - 1) / threads));
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < pending.size(); i += chunkSize) {
            List<PendingTrack> chunk = pending.subList(i, Math.min(i + chunkSize, pending.size()));
            tasks.add(() -> {
                chunk.forEach(track -> addToLibrary(track, parsed));
                return null;
            });
        }

        try {
            for (Future<Void> future : parseExecutor(threads).invokeAll(tasks)) {
                // 走到这里说明任务已结束，get() 只用于把任务内的异常穿出来
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("⚠️  扫描线程被中断，本次解析结果不启用");
            return null;
        } catch (ExecutionException e) {
            // addToLibrary 已吞掉单文件异常，能到这里的只可能是 OOM 之类的非预期错误
            System.err.println("❌ 元数据解析出现非预期错误，本次解析结果不启用: " + e.getCause());
            return null;
        }
        return parsed;
    }

    /**
     * 解析单个文件并入库；任何失败都只跳过该文件，不影响整次扫描。
     *
     * <p>失败的文件不会进入缓存，因此下一次重扫还会再试一次——元数据读取失败往往是暂时性的
     * （文件正被写入、外置盘刚挂载等），比直接拉黑更合适。</p>
     */
    private void addToLibrary(PendingTrack track, Map<String, MusicFile> parsed) {
        try {
            MusicFile musicFile = createMusicFile(track.path(), track.stamp());
            if (musicFile != null) {
                // createMusicFile 里的绝对路径就是缓存 key，直接沿用，避免重复拼接
                parsed.put(musicFile.getFilePath(), musicFile);
            }
        } catch (Exception e) {
            // Skip problematic files
        }
    }

    /** 解析线程数：取配置值，缺失时用默认值（CPU 核数），并夹在合法区间内。 */
    private int resolveThreadCount(ConfigManager.Config config) {
        Integer configured = config.getScanThreads();
        int threads = configured != null ? configured : ConfigManager.DEFAULT_SCAN_THREADS;
        return Math.max(1, Math.min(threads, ConfigManager.MAX_SCAN_THREADS));
    }

    /**
     * 取得（必要时创建）解析线程池。线程数变化时重建，因此改配置无需重启即可生效。
     */
    private ExecutorService parseExecutor(int threads) {
        synchronized (parseExecutorLock) {
            if (parseExecutor != null && parseExecutorThreads == threads) {
                return parseExecutor;
            }
            if (parseExecutor != null) {
                parseExecutor.shutdown();
            }
            AtomicInteger seq = new AtomicInteger();
            parseExecutor = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "music-parse-" + seq.incrementAndGet());
                // 守护线程：即使扫描未结束也不阻塞 JVM 退出
                thread.setDaemon(true);
                return thread;
            });
            parseExecutorThreads = threads;
            return parseExecutor;
        }
    }

    /** 应用关闭时回收解析线程池，避免残留线程。 */
    @PreDestroy
    public void shutdown() {
        synchronized (parseExecutorLock) {
            if (parseExecutor != null) {
                parseExecutor.shutdownNow();
                parseExecutor = null;
            }
        }
    }

    private boolean isSupportedExtension(Path path, List<String> extensions) {
        String ext = FilenameUtils.getExtension(path.toString()).toLowerCase();
        return extensions.stream().anyMatch(ext::equalsIgnoreCase);
    }

    /**
     * 规范化路径，用作曲库内的稳定标识。
     *
     * <p>优先用 {@link File#getCanonicalPath()}：它会解析符号链接、折叠 {@code .}/{@code ..}、
     * 并按平台规范大小写与分隔符，因此同一文件经软链或不同大小写访问都会得到同一个标识，
     * 避免重复入库。解析失败（如目标不可达）时回退到绝对路径。</p>
     */
    private static String stablePath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    /**
     * 把候选文件变成库记录。
     *
     * <p>指纹由调用方传入而不是在这里重新 stat：扫描已经为每个文件读过一次 size/mtime，
     * 复用同一份快照能保证「判定用的指纹」与「写进库的文件信息」完全一致，也省掉一次 syscall。</p>
     */
    private MusicFile createMusicFile(Path path, FileStamp stamp) {
        try {
            File file = path.toFile();
            // 规范化路径（解析软链、规范大小写）：符号链接或不同大小写访问同一文件会得到同一 ID
            String absolutePath = stablePath(file);

            // ✅ 用规范化路径生成稳定 ID（重启/重新扫描不变；软链/大小写重复问题已解决）
            String id = UUID.nameUUIDFromBytes(absolutePath.getBytes(StandardCharsets.UTF_8)).toString();

            String fileName = file.getName();
            String extension = FilenameUtils.getExtension(fileName);
            LocalDateTime lastModified = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(stamp.lastModifiedMillis()),
                    ZoneId.systemDefault()
            );

            MusicFile musicFile = new MusicFile(
                    id, fileName, absolutePath, stamp.size(), extension, lastModified
            );

            musicFile.setUrl("/api/stream/" + id);

            // 提取元数据与时长（Jaudiotagger，覆盖 mp3/flac/m4a/ogg/wav 等；失败自动兜底）
            try {
                musicFile.setMetadata(MetadataExtractor.extract(file));
            } catch (Exception e) {
                // 单个文件元数据解析失败不影响其入库
            }

            return musicFile;
        } catch (Exception e) {
            return null;
        }
    }

    public List<MusicFile> getAllMusic() {
        return new ArrayList<>(musicLibrary.values());
    }

    public Optional<MusicFile> getMusicById(String id) {
        return Optional.ofNullable(musicLibrary.get(id));
    }

    public List<MusicFile> searchMusic(String query) {
        String lowerQuery = query.toLowerCase();
        Map<String, MusicFile> snapshot = musicLibrary;
        return snapshot.values().stream()
                .filter(file -> {
                    String fileName = file.getFileName().toLowerCase();
                    MusicMetadata metadata = file.getMetadata();
                    if (metadata != null) {
                        return fileName.contains(lowerQuery) ||
                                (metadata.getTitle() != null && metadata.getTitle().toLowerCase().contains(lowerQuery)) ||
                                (metadata.getArtist() != null && metadata.getArtist().toLowerCase().contains(lowerQuery)) ||
                                (metadata.getAlbum() != null && metadata.getAlbum().toLowerCase().contains(lowerQuery));
                    }
                    return fileName.contains(lowerQuery);
                })
                .collect(Collectors.toList());
    }

    public List<MusicFile> getMusicByArtist(String artist) {
        Map<String, MusicFile> snapshot = musicLibrary;
        return snapshot.values().stream()
                .filter(file -> {
                    MusicMetadata metadata = file.getMetadata();
                    return metadata != null &&
                            metadata.getArtist() != null &&
                            metadata.getArtist().equalsIgnoreCase(artist);
                })
                .collect(Collectors.toList());
    }

    public Map<String, List<MusicFile>> getArtists() {
        Map<String, MusicFile> snapshot = musicLibrary;
        return snapshot.values().stream()
                .filter(file -> file.getMetadata() != null && file.getMetadata().getArtist() != null)
                .filter(file -> !file.getMetadata().getArtist().equals("Unknown Artist"))
                .collect(Collectors.groupingBy(
                        file -> file.getMetadata().getArtist(),
                        Collectors.toList()
                ));
    }

    public int getLibrarySize() {
        return musicLibrary.size();
    }

    public boolean isScanning() {
        return scanning.get();
    }

    public long getLastScanTime() {
        return lastScanTime;
    }

    /**
     * 文件指纹：判断「自上次成功解析以来这个文件变过没有」的依据。
     *
     * <p>只用 size + mtime 两个 stat 就能拿到的字段，不读内容，因此比对成本极低。</p>
     */
    private record FileStamp(long size, long lastModifiedMillis) {
        static FileStamp read(Path path) throws IOException {
            return new FileStamp(Files.size(path), Files.getLastModifiedTime(path).toMillis());
        }
    }

    /** 增量缓存里的一条：解析该文件时的指纹，以及当时解析出来的结果。 */
    private record CachedTrack(FileStamp stamp, MusicFile file) {}

    /** 待解析的文件及其指纹；指纹随任务一起传递，解析时不必再 stat 一次。 */
    private record PendingTrack(Path path, FileStamp stamp) {}

    /**
     * 一次扫描的解析计划。
     *
     * @param pending  需要（重新）解析的文件
     * @param reusable 可直接复用上次结果的条目，key 为绝对路径
     * @param stamps   本批次所有候选文件的指纹，key 为绝对路径；解析完成后据此写入新缓存
     */
    private record ScanPlan(List<PendingTrack> pending,
                            Map<String, CachedTrack> reusable,
                            Map<String, FileStamp> stamps) {}
}
