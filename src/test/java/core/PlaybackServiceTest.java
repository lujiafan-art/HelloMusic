/*
 * HelloMusic - 播放服务回归测试（不依赖 Mockito，手写桩替换协作者）
 *
 * 本机 JDK 21 上 Mockito inline 自挂载会失败，因此这里用手写 Fake 子类替换
 * MusicScannerService / ConfigManager，避免引入 Mockito。
 */
package core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaybackServiceTest {

    /** 固定曲库，可按需放入若干假歌曲。 */
    static class FakeScanner extends MusicScannerService {
        private final Map<String, MusicFile> lib = new HashMap<>();

        FakeScanner() {
            super(null); // ConfigManager 在本测试中由 FakeConfig 替代，用不到
        }

        void add(MusicFile f) {
            lib.put(f.getId(), f);
        }

        void remove(String id) {
            lib.remove(id);
        }

        @Override
        public Optional<MusicFile> getMusicById(String id) {
            return Optional.ofNullable(lib.get(id));
        }

        @Override
        public List<MusicFile> getAllMusic() {
            return new ArrayList<>(lib.values());
        }
    }

    /** 把配置读写改成纯内存，避免触达磁盘上的 hellomusic-config.json。 */
    static class FakeConfig extends ConfigManager {
        private List<String> queue;
        private int currentIndex;
        private String lastPlayMode = "FORWARD";
        private int maxQueueSize = ConfigManager.DEFAULT_MAX_QUEUE;

        FakeConfig() {
            super(new ObjectMapper(), Validation.buildDefaultValidatorFactory().getValidator());
        }

        @Override
        public int getMaxQueueSize() {
            return maxQueueSize;
        }

        @Override
        public List<String> getPlaybackQueue() {
            return queue;
        }

        @Override
        public int getCurrentIndex() {
            return currentIndex;
        }

        @Override
        public String getLastPlayMode() {
            return lastPlayMode;
        }

        @Override
        public void savePlaybackQueue(List<String> ids, int index) {
            this.queue = ids;
            this.currentIndex = index;
        }

        @Override
        public void savePlayMode(String mode) {
            this.lastPlayMode = mode;
        }
    }

    private static MusicFile song(String id, int durationSeconds) {
        MusicFile f = new MusicFile(id, id + ".mp3", "/music/" + id + ".mp3", 1024, "mp3", LocalDateTime.now());
        MusicMetadata meta = new MusicMetadata();
        meta.setTitle(id);
        meta.setDuration(durationSeconds);
        f.setMetadata(meta);
        return f;
    }

    /** 「查到即移出」的扫描器：模拟「校验与提交之间被并发重扫移出曲库」的窄竞态。 */
    static class FlappingScanner extends FakeScanner {
        private boolean flapping = false;

        void startFlapping() {
            flapping = true;
        }

        @Override
        public Optional<MusicFile> getMusicById(String id) {
            Optional<MusicFile> r = super.getMusicById(id);
            if (flapping && r.isPresent()) {
                super.remove(id);
            }
            return r;
        }
    }

    private PlaybackService newService(MusicScannerService scanner) {
        return newService(scanner, ConfigManager.DEFAULT_MAX_QUEUE);
    }

    private PlaybackService newService(MusicScannerService scanner, int maxQueueSize) {
        FakeConfig cfg = new FakeConfig();
        cfg.maxQueueSize = maxQueueSize;
        SessionRegistry registry = new SessionRegistry(scanner, cfg);
        QueueManager queueManager = new QueueManager(registry, scanner,
                new PlaylistService(scanner, new ObjectMapper()), cfg);
        PlaybackEngine engine = new PlaybackEngine(registry, scanner, cfg, queueManager,
                new ProgressMapper(), new PreloadService(scanner));
        return new PlaybackService(registry, engine, queueManager);
    }

    @Test
    void playSongByIdAppendsWithoutWipingExistingQueue() {
        // 修复「点曲库里不在当前队列的歌会清空整个队列」：
        // 在 [A, B] 队列中点一首不在队列里的 C，应把 C 追加到队尾，而不是替换成全库
        FakeScanner scanner = new FakeScanner();
        scanner.add(song("A", 100));
        scanner.add(song("B", 100));
        scanner.add(song("C", 100));

        PlaybackService svc = newService(scanner);
        svc.loadPlaylist(List.of("A", "B"), "0");
        svc.playSongById("A", "0"); // 播放已在队列里的 A（不应改变队列）
        Map<String, Object> r = svc.playSongById("C", "0"); // 点队列外的 C

        assertNotNull(r, "点存在的歌应返回进度快照");
        assertEquals(List.of("A", "B", "C"), svc.getQueue("0"),
                "点队列外歌曲应追加，而不是清空原有队列");
        assertEquals("C", svc.getCurrentSong("0").getId(), "应切到刚点的那首");
        assertEquals("C", r.get("songId"), "select 应返回当前歌曲元数据快照");
    }

    @Test
    void playSongByIdOnEmptyQueueDoesNotLoadWholeLibrary() {
        // 空队列时点一首歌，应只把那首入队，而不是把整个曲库都灌进队列
        FakeScanner scanner = new FakeScanner();
        scanner.add(song("A", 100));
        scanner.add(song("B", 100));
        scanner.add(song("C", 100));

        PlaybackService svc = newService(scanner);
        Map<String, Object> r = svc.playSongById("B", "0");

        assertNotNull(r);
        assertEquals(List.of("B"), svc.getQueue("0"),
                "空队列点歌应只把该曲入队，而非载入全部曲库");
    }

    @Test
    void playSongByIdLocatesExistingSongWithoutDuplicate() {
        // 回归：队列里已存在该曲时，应只定位、不再重复追加
        FakeScanner scanner = new FakeScanner();
        scanner.add(song("A", 100));
        scanner.add(song("B", 100));
        scanner.add(song("C", 100));

        PlaybackService svc = newService(scanner);
        svc.loadPlaylist(List.of("A", "B", "C"), "0");
        svc.playSongById("B", "0");

        assertEquals(3, svc.getQueue("0").size(), "队列里已有该曲，不应重复追加");
        assertEquals("B", svc.getCurrentSong("0").getId());
    }

    @Test
    void playSongByIdReturnsNullForUnknownSong() {
        // 不存在的歌：返回 null，且不改变队列
        FakeScanner scanner = new FakeScanner();
        scanner.add(song("A", 100));

        PlaybackService svc = newService(scanner);
        svc.loadPlaylist(List.of("A"), "0");
        Map<String, Object> r = svc.playSongById("ZZZ", "0");

        assertNull(r, "不存在的歌应返回 null");
        assertEquals(List.of("A"), svc.getQueue("0"), "点不存在的歌不应改变队列");
        assertEquals("unknown", svc.getProgress("0").get("status"), "服务端不持有时钟，status 应为 unknown");
    }

    @Test
    void nextSkipsGhostItemAndDoesNotLeaveCurrentEmpty() {
        // 修复「幽灵队列项」bug：加载后某首歌被移出曲库（文件删除），next 命中它时
        // 旧实现返回 null 却把游标停在失效项上，导致 /current 返回 empty。
        FakeScanner scanner = new FakeScanner();
        scanner.add(song("A", 100));
        scanner.add(song("G", 100));
        scanner.add(song("B", 100));

        PlaybackService svc = newService(scanner);
        svc.loadPlaylist(List.of("A", "G", "B"), "0"); // 加载时三者都还在曲库，不会被过滤
        scanner.remove("G"); // 模拟 G 在加载后被删除，成为幽灵项

        MusicFile next = svc.next("0");

        assertNotNull(next, "next 应跳过幽灵项 G 并切到 B");
        assertEquals("B", next.getId());
        assertNotNull(svc.getCurrentSong("0"), "/current 不应因幽灵项而为空");
        assertEquals("B", svc.getCurrentSong("0").getId());
        assertEquals(List.of("A", "B"), svc.getQueue("0"), "幽灵项 G 应被就地移除");
    }

    @Test
    void previousSkipsGhostItemAndDoesNotLeaveCurrentEmpty() {
        // previous 同病：命中幽灵项时也不得把游标停在失效项上
        FakeScanner scanner = new FakeScanner();
        scanner.add(song("A", 100));
        scanner.add(song("G", 100));
        scanner.add(song("B", 100));

        PlaybackService svc = newService(scanner);
        svc.loadPlaylist(List.of("A", "G", "B"), "0");
        svc.playSongById("B", "0"); // 定位到 B（下标 2）
        scanner.remove("G"); // G 变幽灵

        MusicFile prev = svc.previous("0");

        assertNotNull(prev, "previous 应跳过幽灵项 G 并切到 A");
        assertEquals("A", prev.getId());
        assertEquals("A", svc.getCurrentSong("0").getId());
        assertEquals(List.of("A", "B"), svc.getQueue("0"));
    }

    @Test
    void nextResolvesOnceWithoutSecondLookup() {
        // 修复「校验后重查」窄竞态：advanceAndCommit 只查一次曲库并用该对象提交。
        // 用「查到即移出」的假扫描器模拟「校验通过后、重查前该曲被移出」——旧实现会因
        // 第二次查询返回空而让 next 返回 null；新实现只查一次，next 应返回该曲。
        FlappingScanner scanner = new FlappingScanner();
        scanner.add(song("A", 100));
        scanner.add(song("B", 100));

        PlaybackService svc = newService(scanner);
        svc.loadPlaylist(List.of("A", "B"), "0");
        scanner.startFlapping(); // 此后每次 getMusicById 命中即移出

        MusicFile next = svc.next("0");

        assertNotNull(next, "next 只应查一次曲库，不应因二次查询而失败");
        assertEquals("B", next.getId());
        assertEquals(List.of("A", "B"), svc.getQueue("0"), "队列不应被破坏");
    }

    @Test
    void previousResolvesOnceWithoutSecondLookup() {
        FlappingScanner scanner = new FlappingScanner();
        scanner.add(song("A", 100));
        scanner.add(song("B", 100));

        PlaybackService svc = newService(scanner);
        svc.loadPlaylist(List.of("A", "B"), "0");
        svc.playSongById("B", "0"); // 定位到 B（下标 1）
        scanner.startFlapping();

        MusicFile prev = svc.previous("0");

        assertNotNull(prev, "previous 只应查一次曲库，不应因二次查询而失败");
        assertEquals("A", prev.getId());
        assertEquals(List.of("A", "B"), svc.getQueue("0"));
    }

    @Test
    void progressDoesNotExposePositionOrServerClockFields() {
        // 负向守护：位置归客户端，服务器不推算「现在播到第几秒」。progress 的 data 里不得含
        // "position" / "serverTime"（服务端权威时钟字段），也不得含 "lastSyncedAt" / "syncDueAt"
        // （客户端同步对账字段）。若有人重新引入服务端时钟，这条测试会失败，提醒他先读
        // README「设计约定」。
        FakeScanner scanner = new FakeScanner();
        scanner.add(song("A", 100));

        PlaybackService svc = newService(scanner);
        svc.loadPlaylist(List.of("A"), "0");

        Map<String, Object> progress = svc.getProgress("0");
        assertNotNull(progress);
        assertFalse(progress.containsKey("position"), "progress 不应推算播放位置");
        assertFalse(progress.containsKey("serverTime"), "progress 不应携带权威时间戳");
        assertFalse(progress.containsKey("lastSyncedAt"), "progress 不应含同步对账字段");
        assertFalse(progress.containsKey("syncDueAt"), "progress 不应含同步对账字段");
    }

    @Test
    void addToQueueDeduplicatesAlreadyPresentSong() {
        // 修复「注释声称去重、实现未去重」：queue.add 追加已在队列的歌曲时应返回
        // ALREADY_PRESENT，而不是重复追加。
        FakeScanner scanner = new FakeScanner();
        scanner.add(song("A", 100));
        scanner.add(song("B", 100));

        PlaybackService svc = newService(scanner);
        svc.loadPlaylist(List.of("A"), "0");

        assertEquals(QueueManager.AddResult.ADDED, svc.addToQueue("B", "0"), "首次添加 B 应成功");
        assertEquals(QueueManager.AddResult.ALREADY_PRESENT, svc.addToQueue("B", "0"),
                "重复添加 B 应返回已存在");
        assertEquals(List.of("A", "B"), svc.getQueue("0"), "重复添加不应追加重复项");
    }

    @Test
    void addToQueueRejectsWhenQueueFull() {
        // 上限检查：队列达到 maxQueueSize 后再追加应返回 QUEUE_FULL，且不改变队列。
        FakeScanner scanner = new FakeScanner();
        scanner.add(song("A", 100));
        scanner.add(song("B", 100));
        scanner.add(song("C", 100));

        PlaybackService svc = newService(scanner, 2);
        svc.loadPlaylist(List.of("A"), "0");

        assertEquals(QueueManager.AddResult.ADDED, svc.addToQueue("B", "0"));
        assertEquals(QueueManager.AddResult.QUEUE_FULL, svc.addToQueue("C", "0"), "队列满应返回 QUEUE_FULL");
        assertEquals(List.of("A", "B"), svc.getQueue("0"), "超限歌曲不应入队");
    }

    @Test
    void progressTitleFallsBackToFileNameWhenMetadataUnknown() {
        // 修复「显示名绕过 MusicFile.displayTitle()」：元数据 title 为占位符 "Unknown Title" 时，
        // progress 应回退文件名，而非把占位符原样返回（README 约定「显示名唯一实现」）。
        FakeScanner scanner = new FakeScanner();
        MusicFile f = new MusicFile("A", "MySong.mp3", "/music/MySong.mp3", 1024, "mp3", LocalDateTime.now());
        MusicMetadata meta = new MusicMetadata(); // title 默认 "Unknown Title"，artist 默认 "Unknown Artist"
        meta.setDuration(100);
        f.setMetadata(meta);
        scanner.add(f);

        PlaybackService svc = newService(scanner);
        svc.loadPlaylist(List.of("A"), "0");

        Map<String, Object> progress = svc.getProgress("0");
        assertEquals("MySong.mp3", progress.get("title"), "标题为占位符时应回退文件名");
        assertNull(progress.get("artist"), "艺术家为占位符时 displayArtist 应返回 null");
    }
}
