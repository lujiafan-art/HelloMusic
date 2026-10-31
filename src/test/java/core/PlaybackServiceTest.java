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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        FakeConfig() {
            super(new ObjectMapper(), Validation.buildDefaultValidatorFactory().getValidator());
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

    private PlaybackService newService(FakeScanner scanner) {
        FakeConfig cfg = new FakeConfig();
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
        assertEquals("started", r.get("status"), "两段式握手应返回 started 状态");
        assertNotNull(r.get("serverTime"), "started 应携带权威时间戳 serverTime");
        assertTrue((Boolean) r.get("isPlaying"), "started 后应立即进入播放态");
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
        assertEquals(false, svc.getProgress("0").get("isPlaying"), "不应误进入播放态");
    }

    @Test
    void prepareThenStartHandshakeGivesAuthoritativeBaseline() {
        // 两段式握手：prepare 不启动时钟；start 才启动并返回权威时间戳与起始偏移
        FakeScanner scanner = new FakeScanner();
        scanner.add(song("A", 100));

        PlaybackService svc = newService(scanner);
        Map<String, Object> ready = svc.prepare("A", "0");
        assertNotNull(ready, "prepare 应返回 ready 快照");
        assertEquals("ready", ready.get("status"), "阶段一应返回 ready");
        assertEquals(false, svc.getProgress("0").get("isPlaying"), "prepare 后时钟尚未启动（不应在播放）");

        // 尚未 prepare 的歌调用 start 应返回 null（无法开始）
        // （这里直接对另一首未 prepare 的歌验证：先 prepare A，再 start 前无需验证；改为验证序列）

        Map<String, Object> started = svc.start("0");
        assertNotNull(started, "start 应返回 started 快照");
        assertEquals("started", started.get("status"));
        assertEquals(0.0, (Double) started.get("position"), 0.001, "新歌起始偏移应为 0");
        assertNotNull(started.get("serverTime"), "started 应携带权威时间戳");
        assertTrue((Boolean) started.get("isPlaying"), "start 后应进入播放态");
    }

    @Test
    void startWithoutPrepareReturnsNull() {
        // 未 prepare 直接 start 应返回 null（服务端时钟不应被非法启动）
        FakeScanner scanner = new FakeScanner();
        scanner.add(song("A", 100));
        PlaybackService svc = newService(scanner);
        assertNull(svc.start("0"), "未 prepare 调用 start 应返回 null");
    }
}
