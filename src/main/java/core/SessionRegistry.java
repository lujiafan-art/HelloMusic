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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 前端会话注册表：会话的「创建、心跳、断开、超时治理」的唯一拥有者。
 *
 * <p>原 {@link PlaybackService} 把会话治理与播放控制、队列管理、进度组装揉在一个类里，
 * 现拆出本类，只负责 {@code sessions} 这份状态及其生命周期；播放状态本身仍挂在每个
 * {@link ClientSession} 内的 {@link PlaybackSession} 上，由 {@link PlaybackEngine} /
 * {@link QueueManager} 通过 {@link #playbackSession(String)} 取用。</p>
 *
 * <p><b>会话 ID 约定：</b>会话以数字 ID 标识（{@code "0"}、{@code "1"}、{@code "2"}…）。
 * {@code "0"} 是<strong>起始会话</strong>——第一个连接的客户端分到它，同时也是「兼容模式」
 * （{@code enableHeartbeat=0}）与「未带 sessionId 请求」回退到的会话。旧有的 {@code "default"}
 * 字样不再作为会话 key，仅作为兼容模式下<strong>对外</strong>暴露的别名，统一归一化为
 * {@code "0"}。ID 分配遵循「0 起始递增、复用最小空闲数字」：会话断开或超时删除后其数字进入
 * 空闲池，下一个连接优先复用「小于当前最大已分配数字」的最小空闲数字。</p>
 */
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    /** 起始 / 兼容会话的内部 ID（数字 0）。第一个连接的客户端分到它，兼容模式也复用它。 */
    public static final String START_SESSION_ID = "0";
    /** 兼容模式（enableHeartbeat=0）下对外暴露的会话别名，内部映射到 {@link #START_SESSION_ID}。 */
    public static final String COMPAT_SESSION_ALIAS = "default";
    /** 起始会话尚未被真实客户端占用时的占位 clientIp，用于识别「兼容兜底」身份。 */
    private static final String LEGACY_CLIENT_IP = "legacy";

    /** 心跳期望间隔：客户端应每这么久上报一次心跳（毫秒）。 */
    private static final long HEARTBEAT_EXPECTED_MS = 60 * 1000;
    /** 心跳超时（连续未达该时长）→ 记为 stale（仅记录日志；服务端没有可暂停的播放状态）。 */
    private static final long SESSION_PAUSE_AFTER_MS = 2 * HEARTBEAT_EXPECTED_MS;
    /** 会话无响应超过该时长（毫秒）→ 删除会话 ID 并结束会话。 */
    private static final long SESSION_DELETE_AFTER_MS = 3 * HEARTBEAT_EXPECTED_MS;
    /** 会话巡检周期：每 30 秒一次，做「心跳存活治理」。 */
    private static final long SESSION_INSPECTION_PERIOD_MS = 30 * 1000;

    /** 每个前端客户端一个独立会话（key 为数字 ID 字符串；"0" 为起始 / 兼容会话）。 */
    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();

    private final MusicScannerService scannerService;
    private final ConfigManager configManager;
    /** 起始会话的初始播放模式（构造器解析一次，懒重建起始会话时复用）。 */
    private final PlayMode initialPlayMode;

    /** 下一个待分配的全新数字 ID（0 已由起始会话占用，故从 1 开始）。仅在 synchronized 内读写。 */
    private int nextSessionId = 1;
    /** 已被释放、可复用的空闲数字 ID（按自然序，最小优先）。仅在 synchronized 内读写。 */
    private final TreeSet<Integer> freeSessionIds = new TreeSet<>();

    public SessionRegistry(MusicScannerService scannerService,
                           ConfigManager configManager) {
        this.scannerService = scannerService;
        this.configManager = configManager;
        this.initialPlayMode = parseMode(configManager.getLastPlayMode());

        // 起始会话：先建立会话本体并恢复播放模式（不依赖曲库），放入 map 供后续请求取用。
        // 队列恢复放到 {@link #restoreDefaultSessionQueue()}：它依赖曲库已扫描完成，
        // 而扫描由 main 在装配完成后调用 MusicScannerService.initScan() 执行——若在构造器里做，
        // 就隐式依赖初始化顺序（扫描改成异步后这里会拿到空库、过滤掉全部 ID）。
        ClientSession start = new ClientSession(START_SESSION_ID, LEGACY_CLIENT_IP, "local");
        start.getPlayback().setPlayMode(initialPlayMode);
        this.sessions.put(START_SESSION_ID, start);
    }

    /**
     * 恢复起始会话上次保存的播放队列 + 播放位置。
     *
     * <p>由 main 在装配完成后、MusicScannerService 的启动扫描（{@code initScan()}）之后
     * 显式调用，而非构造器：此时曲库已扫描完成，按 ID 过滤恢复队列不会拿到空库。
     * 相比构造器，解除了「初始化顺序」这条隐式耦合。</p>
     */
    public void restoreDefaultSessionQueue() {
        // 「是否自动保存列表」= 0 时不读也不写，现场不跨重启保留（与 QueueManager.persistQueue 的开关保持一致）
        List<String> savedQueue = configManager.isAutoSaveQueue() ? configManager.getPlaybackQueue() : null;
        if (savedQueue == null || savedQueue.isEmpty()) {
            return;
        }
        List<String> valid = savedQueue.stream()
                .filter(id -> id != null && scannerService.getMusicById(id).isPresent())
                .toList();
        if (valid.isEmpty()) {
            return;
        }
        PlaybackSession startPlayback = startSession().getPlayback();
        startPlayback.setPlaylist(valid);
        startPlayback.setCurrentIndex(configManager.getCurrentIndex());
        log.info("已恢复上次播放队列: " + valid.size() + " 首（位置 "
                + (configManager.getCurrentIndex() + 1) + "/" + valid.size() + "）");
    }

    // ===== 会话管理（连接 / 心跳 / 超时治理） =====

    /**
     * 客户端「连接」：创建一个独立的前端会话并返回会话 ID。
     *
     * @param clientIp   客户端自报的 IP（本机约定为 127.0.0.1 而非 localhost）
     * @param remoteAddr 服务端从请求观测到的真实对端地址
     * @return 连接成功快照（含 sessionId / heartbeatIntervalMs / 超时阈值）
     */
    public Map<String, Object> connect(String clientIp, String remoteAddr) {
        Map<String, Object> r = new HashMap<>();
        r.put("serverTime", System.currentTimeMillis());

        // 心跳未启用：不建立持续连接，所有前端共用唯一的起始会话（0 号）。
        // 对外仍返回旧别名 "default" 以保持兼容；仍返回与「心跳启用」分支相同的字段集
        // （heartbeatIntervalMs / 超时阈值仅作信息），避免客户端按统一契约解析时因字段缺失而 NPE。
        if (!configManager.isEnableHeartbeat()) {
            r.put("status", "connected");
            r.put("sessionId", COMPAT_SESSION_ALIAS);
            r.put("heartbeatEnabled", false);
            r.put("heartbeatIntervalMs", HEARTBEAT_EXPECTED_MS);
            r.put("sessionPauseAfterMs", SESSION_PAUSE_AFTER_MS);
            r.put("sessionDeleteAfterMs", SESSION_DELETE_AFTER_MS);
            return r;
        }

        ClientSession cs = registerSession(clientIp, remoteAddr);
        warnIfNonLoopback(cs.getClientIp());
        r.put("status", "connected");
        r.put("sessionId", cs.getSessionId());
        r.put("heartbeatEnabled", true);
        r.put("heartbeatIntervalMs", HEARTBEAT_EXPECTED_MS);
        r.put("sessionPauseAfterMs", SESSION_PAUSE_AFTER_MS);
        r.put("sessionDeleteAfterMs", SESSION_DELETE_AFTER_MS);
        log.info("[会话] 新前端已连接：sessionId=" + cs.getSessionId() + "，客户端IP=" + cs.getClientIp()
                + "，remoteAddr=" + cs.getRemoteAddr());
        return r;
    }

    /**
     * 原子地「分配空闲 ID + 构造会话 + 注册进 map」。
     *
     * <p>必须整体持锁：若把「分配」与「{@code sessions.put}」拆成两步（分配持锁、put 在锁外），
     * 两步之间 0 号会处于「已从空闲池取出、但尚未写回 map」的中间态。此时并发触发的
     * {@link #startSession()} 会把「0 号不在 map」误判为「0 号空闲」，懒重建一个 legacy 会话，
     * 与即将写入的真实会话产生 ID 冲突。整体持锁后，分配与注册相对 {@link #startSession()} 的
     * double-checked 重建是原子的，不再有冲突窗口。</p>
     */
    private synchronized ClientSession registerSession(String clientIp, String remoteAddr) {
        String id = allocateSessionId();
        ClientSession cs = new ClientSession(id,
                clientIp != null ? clientIp : "unknown",
                remoteAddr != null ? remoteAddr : "unknown");
        // 分配 0 号时覆盖掉 legacy 起始会话，完成「转正」为真实客户端
        sessions.put(id, cs);
        return cs;
    }

    /**
     * 校验客户端自报的 IP 是否为本机约定（回环地址 127.0.0.1）。
     *
     * <p>本服务面向局域网内的本地播放场景，客户端应当自报回环地址；若自报的是局域网 IP
     * 或无法解析的字符串，仅记录一条 WARN 提示差异——<strong>不影响</strong>会话创建，
     * 因为 {@code clientIp} 只是展示用字段，真实对端地址由服务端从请求中观测得到。</p>
     */
    private void warnIfNonLoopback(String clientIp) {
        if ("unknown".equals(clientIp)) {
            return; // 未自报，无从校验
        }
        try {
            if (!InetAddress.getByName(clientIp).isLoopbackAddress()) {
                log.warn("[会话] 客户端自报 IP 不是回环地址（本机约定为 127.0.0.1）: " + clientIp);
            }
        } catch (Exception e) {
            log.warn("[会话] 客户端自报 IP 无法解析: " + clientIp);
        }
    }

    /** 客户端上报心跳：刷新会话存活时间。返回是否成功（会话不存在则返回 false）。 */
    public boolean heartbeat(String sessionId) {
        // 心跳未启用：不需要保活，调用视为成功但不做任何事
        if (!configManager.isEnableHeartbeat()) {
            return true;
        }
        String key = normalizeSessionId(sessionId);
        // 未带 sessionId：兼容兜底，视为成功但不保活、也不误报（避免触发客户端误重连）
        if (key == null) {
            return true;
        }
        ClientSession cs = sessions.get(key);
        if (cs == null) {
            return false;
        }
        // 起始会话尚未被真实客户端占用（legacy 兜底）：不保活，但视为成功
        if (LEGACY_CLIENT_IP.equals(cs.getClientIp())) {
            return true;
        }
        cs.touch();
        return true;
    }

    /** 客户端主动断开：结束并移除会话（legacy 起始会话不参与）。返回是否成功。 */
    public boolean disconnect(String sessionId) {
        String key = normalizeSessionId(sessionId);
        if (key == null) {
            return false;
        }
        // 起始会话只有在「已被真实客户端占用」（非 legacy 兜底）时才可断开
        if (START_SESSION_ID.equals(key)) {
            ClientSession zero = sessions.get(START_SESSION_ID);
            if (zero == null || LEGACY_CLIENT_IP.equals(zero.getClientIp())) {
                return false;
            }
        }
        ClientSession removed = removeSession(key);
        if (removed != null) {
            removed.markEnded();
            log.info("[会话] 客户端主动断开：sessionId=" + key
                    + "，客户端IP=" + removed.getClientIp());
            return true;
        }
        return false;
    }

    /** 查询某会话的状态快照（调试 / 前端自检用）。 */
    public Map<String, Object> sessionStatus(String sessionId) {
        String key = normalizeSessionId(sessionId);
        ClientSession cs = key != null ? sessions.get(key) : null;
        Map<String, Object> r = new HashMap<>();
        if (cs == null) {
            r.put("status", "not_found");
            return r;
        }
        long now = System.currentTimeMillis();
        r.put("status", "alive");
        r.put("sessionId", cs.getSessionId());
        r.put("clientIp", cs.getClientIp());
        r.put("remoteAddr", cs.getRemoteAddr());
        r.put("lastHeartbeatEpoch", cs.getLastHeartbeatEpoch());
        r.put("sinceLastHeartbeatMs", now - cs.getLastHeartbeatEpoch());
        r.put("heartbeatIntervalMs", HEARTBEAT_EXPECTED_MS);
        r.put("sessionPauseAfterMs", SESSION_PAUSE_AFTER_MS);
        r.put("sessionDeleteAfterMs", SESSION_DELETE_AFTER_MS);
        return r;
    }

    /**
     * 统一的会话巡检任务：每 {@link #SESSION_INSPECTION_PERIOD_MS} 跑一次（由外部调度器调用），
     * 单次遍历所有前端会话，只做「心跳存活治理」。legacy 起始会话（未被真实客户端占用）永不参与巡检。
     *
     * <ul>
     *   <li>心跳超时：记录一条 stale 日志（服务端没有可暂停的播放状态，不做任何状态改动）；</li>
     *   <li>超过 {@link #SESSION_DELETE_AFTER_MS}：结束并移除会话。</li>
     * </ul>
     */
    public void inspectSessions() {
        // 「是否启用心跳包」= 0 时后端完全放弃会话治理：不记录 stale、不删除。
        // 此时本就不存在独立前端会话（所有前端共用 0 号起始会话），巡检无事可做。
        if (!configManager.isEnableHeartbeat()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        for (ClientSession cs : sessions.values()) {
            // legacy 起始会话（0 号未被真实客户端占用）是兼容兜底，不参与治理
            if (LEGACY_CLIENT_IP.equals(cs.getClientIp())) {
                continue;
            }

            long since = now - cs.getLastHeartbeatEpoch();
            if (since >= SESSION_PAUSE_AFTER_MS) {
                log.warn("[会话] 会话 " + cs.getSessionId() + "（客户端 " + cs.getClientIp()
                        + "）心跳超时（" + (since / 1000) + " 秒未响应）");
            }
            if (since >= SESSION_DELETE_AFTER_MS) {
                toRemove.add(cs.getSessionId());
            }
        }
        for (String id : toRemove) {
            ClientSession removed = removeSession(id);
            if (removed != null) {
                removed.markEnded();
                log.info("[会话] 会话 " + id + "（客户端 " + removed.getClientIp()
                        + "）超过 " + (SESSION_DELETE_AFTER_MS / 60000)
                        + " 分钟无响应，已结束并删除");
            }
        }
    }

    // ===== 会话解析 =====

    /** 取某会话的播放状态；sessionId 为空或未知时回退到起始会话（0 号）。 */
    public PlaybackSession playbackSession(String sessionId) {
        return resolveSession(sessionId).getPlayback();
    }

    /** 取起始会话（0 号）的播放状态。 */
    public PlaybackSession playbackSession() {
        return startSession().getPlayback();
    }

    /**
     * 判断该会话标识是否指向起始会话（0 号）。
     * 未带 sessionId（{@code null}/空白）与兼容别名 {@code "default"} 都视为起始会话，
     * 供播放模式 / 播放队列的「仅起始会话持久化」判断使用。
     */
    public boolean isStartSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return true;
        }
        return START_SESSION_ID.equals(normalizeSessionId(sessionId));
    }

    /** 解析会话：传入的 sessionId 有效则取其会话，否则回退到起始会话（0 号）。 */
    private ClientSession resolveSession(String sessionId) {
        // 心跳未启用：不支持多会话，所有前端一律共用 0 号起始会话（队列与进度共享）
        if (!configManager.isEnableHeartbeat()) {
            return startSession();
        }
        // 只查一次：若用 containsKey + get 两次查找，并发 disconnect 可能在两次之间把会话移除，
        // 造成 containsKey=true 但 get=null → resolveSession 返回 null → 调用方 NPE（500）。
        // 单次 get 后判 null，回退到起始会话，消除这个竞态窗口。
        String key = normalizeSessionId(sessionId);
        ClientSession cs = key != null ? sessions.get(key) : null;
        return cs != null ? cs : startSession();
    }

    /**
     * 保证起始会话（0 号）存在；被释放后按需懒重建为 legacy 兜底。
     *
     * <p>采用 double-checked locking：快速路径无锁读，慢路径在 {@code synchronized(this)} 内
     * 复查后重建。由于「0 号」的所有写入（{@link #registerSession} 的转正覆盖、
     * {@link #removeSession} 的移除与释放）都在同一把锁内完成，这里的复查是权威的，不会把
     * 「已分配但尚未注册」或「已移除但尚未放回空闲池」的中间态误判为空闲而重复重建。</p>
     */
    private ClientSession startSession() {
        ClientSession cs = sessions.get(START_SESSION_ID);
        if (cs != null) {
            return cs;
        }
        synchronized (this) {
            cs = sessions.get(START_SESSION_ID);
            if (cs != null) {
                return cs;
            }
            ClientSession fresh = new ClientSession(START_SESSION_ID, LEGACY_CLIENT_IP, "local");
            fresh.getPlayback().setPlayMode(initialPlayMode);
            sessions.put(START_SESSION_ID, fresh);
            freeSessionIds.remove(0); // 0 号被兜底重新占用，不再算空闲
            return fresh;
        }
    }

    /**
     * 分配下一个空闲会话 ID：0 起始、递增，并复用「小于当前最大已分配数字」的最小空闲数字。
     * 若起始会话（0 号）尚未被真实客户端占用，则优先把它转正返回 {@code "0"}。
     */
    private synchronized String allocateSessionId() {
        ClientSession zero = sessions.get(START_SESSION_ID);
        if (zero != null && LEGACY_CLIENT_IP.equals(zero.getClientIp())) {
            return START_SESSION_ID; // 起始会话仍是兼容兜底：转正（connect 里覆盖）
        }
        if (!freeSessionIds.isEmpty()) {
            return String.valueOf(freeSessionIds.pollFirst()); // 最小空闲数字优先
        }
        return String.valueOf(nextSessionId++);
    }

    /**
     * 原子地「从 map 移除会话 + 把其 ID 放回空闲池」。
     *
     * <p>两步必须合并在同一把锁内：若先 {@code sessions.remove} 再放回空闲池（中间有窗口），
     * 并发触发的 {@link #startSession()} 会把「0 号已不在 map」误判为「0 号空闲」而懒重建 legacy，
     * 与即将放回空闲池的 0 号叠加，形成「0 号既被 legacy 占用、又躺在空闲池里」的双重占用——
     * 下一个连接就会把 0 号二次分配给另一个客户端。</p>
     *
     * @return 被移除的会话；ID 不存在时返回 {@code null}
     */
    private synchronized ClientSession removeSession(String id) {
        ClientSession removed = sessions.remove(id);
        if (removed != null) {
            try {
                freeSessionIds.add(Integer.parseInt(id));
            } catch (NumberFormatException ignored) {
                // 会话 ID 恒为数字字符串，此处仅为防御，不应发生
            }
        }
        return removed;
    }

    /** 归一化会话 ID：兼容别名 "default" → 起始会话 "0"；null/空白 → null。 */
    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return COMPAT_SESSION_ALIAS.equals(sessionId) ? START_SESSION_ID : sessionId;
    }

    /** 安全解析配置中的播放模式名，非法值回退到 FORWARD。 */
    private PlayMode parseMode(String name) {
        if (name == null) {
            return PlayMode.FORWARD;
        }
        try {
            return PlayMode.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PlayMode.FORWARD;
        }
    }
}
