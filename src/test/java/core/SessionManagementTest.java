/*
 * 会话管理回归测试（连接 / 心跳 / 超时 stale / 超时删除），不依赖 Mockito。
 *
 * 心跳超时阈值以常量给出（2 分钟 stale、3 分钟删除）。为在单测里确定性地触发，
 * 用反射把 ClientSession 的 lastHeartbeatEpoch 回拨到「很久以前」，再调用
 * inspectSessions() 验证 stale / 删除行为，避免真的等待数分钟。
 */
package core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagementTest {

    static class FakeScanner extends MusicScannerService {
        private final Map<String, MusicFile> lib = new HashMap<>();
        FakeScanner() { super(null); }
        void add(MusicFile f) { lib.put(f.getId(), f); }
        @Override public Optional<MusicFile> getMusicById(String id) { return Optional.ofNullable(lib.get(id)); }
        @Override public List<MusicFile> getAllMusic() { return new ArrayList<>(lib.values()); }
    }

    static class FakeConfig extends ConfigManager {
        FakeConfig() { super(new ObjectMapper(), Validation.buildDefaultValidatorFactory().getValidator()); }
        @Override public List<String> getPlaybackQueue() { return null; }
        @Override public int getCurrentIndex() { return 0; }
        @Override public String getLastPlayMode() { return "FORWARD"; }
        @Override public void savePlaybackQueue(List<String> ids, int index) {}
        @Override public void savePlayMode(String mode) {}
    }

    private static MusicFile song(String id, int dur) {
        MusicFile f = new MusicFile(id, id + ".mp3", "/m/" + id + ".mp3", 1024, "mp3", LocalDateTime.now());
        MusicMetadata m = new MusicMetadata();
        m.setTitle(id); m.setDuration(dur);
        f.setMetadata(m);
        return f;
    }

    private SessionRegistry registry;
    private PlaybackService svc;

    private void newService() {
        FakeScanner s = new FakeScanner();
        s.add(song("A", 100));
        FakeConfig cfg = new FakeConfig();
        registry = new SessionRegistry(s, cfg);
        QueueManager qm = new QueueManager(registry, s, new PlaylistService(s, new ObjectMapper()), cfg);
        PlaybackEngine engine = new PlaybackEngine(registry, s, cfg, qm, new ProgressMapper(), new PreloadService(s));
        svc = new PlaybackService(registry, engine, qm);
    }

    /** 回拨某会话的心跳时间，模拟「已 N 毫秒未上报心跳」。 */
    private void backdateHeartbeat(String sessionId, long msAgo) throws Exception {
        Field f = SessionRegistry.class.getDeclaredField("sessions");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ClientSession> map = (Map<String, ClientSession>) f.get(registry);
        ClientSession cs = map.get(sessionId);
        Field hb = ClientSession.class.getDeclaredField("lastHeartbeatEpoch");
        hb.setAccessible(true);
        hb.set(cs, System.currentTimeMillis() - msAgo);
    }

    /** 读取空闲 ID 池（仅测试用于校验一致性）。 */
    @SuppressWarnings("unchecked")
    private Set<Integer> freePool() throws Exception {
        Field f = SessionRegistry.class.getDeclaredField("freeSessionIds");
        f.setAccessible(true);
        return (Set<Integer>) f.get(registry);
    }

    /** 读取会话 map（仅测试用于校验一致性）。 */
    @SuppressWarnings("unchecked")
    private Map<String, ClientSession> sessionMap() throws Exception {
        Field f = SessionRegistry.class.getDeclaredField("sessions");
        f.setAccessible(true);
        return (Map<String, ClientSession>) f.get(registry);
    }

    @Test
    void connectReturnsSessionIdAndHeartbeatKeepsAlive() {
        newService();
        Map<String, Object> r = svc.connect("127.0.0.1", "127.0.0.1");
        String id = (String) r.get("sessionId");
        assertNotNull(id, "connect 应返回 sessionId");
        assertEquals("0", id, "首个连接应分配到起始会话 0");
        assertEquals("connected", r.get("status"));
        assertEquals(60_000L, r.get("heartbeatIntervalMs"));

        assertTrue(svc.heartbeat(id), "有效会话心跳应成功");
        assertEquals("alive", svc.sessionStatus(id).get("status"));
    }

    @Test
    void sessionIdsAreSequentialAndReuseSmallestFree() {
        newService();
        String id0 = (String) svc.connect("127.0.0.1", "127.0.0.1").get("sessionId");
        String id1 = (String) svc.connect("127.0.0.1", "127.0.0.1").get("sessionId");
        String id2 = (String) svc.connect("127.0.0.1", "127.0.0.1").get("sessionId");
        assertEquals("0", id0);
        assertEquals("1", id1);
        assertEquals("2", id2);

        // 断开中间的 1 号，下一个连接应复用「小于最大已分配数字」的最小空闲数字 1
        svc.disconnect(id1);
        String reused = (String) svc.connect("127.0.0.1", "127.0.0.1").get("sessionId");
        assertEquals("1", reused, "应复用最小空闲数字 1");
    }

    @Test
    void disconnectRemovesSession() {
        newService();
        String id = (String) svc.connect("127.0.0.1", "127.0.0.1").get("sessionId");
        assertTrue(svc.disconnect(id), "断开应成功");
        assertEquals("not_found", svc.sessionStatus(id).get("status"));
    }

    @Test
    void legacyStartSessionCannotBeDisconnected() {
        newService();
        // 起始会话在未被真实客户端占用前（legacy 兜底）不可被断开
        assertFalse(svc.disconnect("0"));
        assertFalse(svc.disconnect("default"), "兼容别名同样不可断开");
    }

    @Test
    void heartbeatTimeoutDoesNotDeleteSession() throws Exception {
        newService();
        String id = (String) svc.connect("127.0.0.1", "127.0.0.1").get("sessionId");

        // 心跳停在 150 秒前：超过 stale 阈值(120s)，但未到删除阈值(180s)。服务端不持有时钟，
        // 此处只记录 stale 日志、不删除会话。
        backdateHeartbeat(id, 150_000L);
        svc.inspectSessions();

        assertEquals("alive", svc.sessionStatus(id).get("status"), "150s 未到删除阈值，会话应仍在");
    }

    @Test
    void noHeartbeatBeyond3MinDeletesSession() throws Exception {
        newService();
        String id = (String) svc.connect("127.0.0.1", "127.0.0.1").get("sessionId");

        // 心跳停在 200 秒前：超过删除阈值(180s)，扫描应结束并删除会话
        backdateHeartbeat(id, 200_000L);
        svc.inspectSessions();

        assertEquals("not_found", svc.sessionStatus(id).get("status"), "超 3 分钟无响应应删除会话");
    }

    @Test
    void legacyStartSessionNeverGovernedBySweep() throws Exception {
        newService();
        // 即便把 0 号的心跳回拨到很久以前，扫描也不应删除它（legacy 兜底不治理）
        backdateHeartbeat("0", 200_000L);
        svc.inspectSessions();
        assertEquals("alive", svc.sessionStatus("0").get("status"),
                "legacy 起始会话不参与心跳治理，不应被删除");
    }

    @Test
    void concurrentConnectDisconnectNeverLeavesIdDoubleOwned() throws Exception {
        // 修复 startSession() 懒重建的 ID 冲突窗口：连接（分配+注册）与断开（移除+释放）
        // 必须各自原子完成，否则 0 号会陷入「既被 legacy 占用、又躺在空闲池」的双重占用，
        // 导致下一个连接把 0 号二次分配。这里用并发压力 + 最终一致性断言锁死该不变式。
        newService();
        int threads = 8;
        int rounds = 150;
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            workers.add(new Thread(() -> {
                try {
                    for (int i = 0; i < rounds; i++) {
                        Map<String, Object> r = svc.connect("127.0.0.1", "127.0.0.1");
                        String id = (String) r.get("sessionId");
                        svc.heartbeat(id);
                        if (i % 3 == 0) {
                            svc.disconnect(id);
                        }
                        // 触发起始会话懒重建（0 号被释放后走 startSession 路径）
                        svc.getProgress("0");
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }
        for (Thread w : workers) {
            w.start();
        }
        for (Thread w : workers) {
            w.join();
        }
        assertNull(failure.get(), () -> "并发连接/断开不应抛异常: " + failure.get());

        // 最终一致性：空闲池与占用 map 不相交——否则存在「同一 ID 被双重占用」
        Set<Integer> free = freePool();
        Map<String, ClientSession> sessions = sessionMap();
        for (Integer id : free) {
            assertFalse(sessions.containsKey(String.valueOf(id)),
                    "空闲池中的 ID 不应仍被会话占用（双重占用）: " + id);
        }
    }
}
