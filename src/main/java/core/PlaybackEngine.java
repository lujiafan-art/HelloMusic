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

import java.util.HashMap;
import java.util.Map;

/**
 * 播放控制引擎：play / pause / seek / next / previous / 播放模式，以及两段式握手
 * prepare + start。
 *
 * <p>原 {@link PlaybackService} 里的播放控制职责抽到本类。会话状态取自
 * {@link SessionRegistry}，进度快照委托 {@link ProgressMapper} 组装，切歌 / 准备阶段
 * 需要落盘队列时回调 {@link QueueManager#persistQueue(String)}。</p>
 */
public class PlaybackEngine {

    private static final Logger log = LoggerFactory.getLogger(PlaybackEngine.class);

    private final SessionRegistry sessions;
    private final MusicScannerService scannerService;
    private final ConfigManager configManager;
    private final QueueManager queueManager;
    private final ProgressMapper progressMapper;
    // 用于「准备」阶段预载歌曲首段缓冲以加速播放（恒非 null）
    private final PreloadService preloadService;

    public PlaybackEngine(SessionRegistry sessions,
                          MusicScannerService scannerService,
                          ConfigManager configManager,
                          QueueManager queueManager,
                          ProgressMapper progressMapper,
                          PreloadService preloadService) {
        this.sessions = sessions;
        this.scannerService = scannerService;
        this.configManager = configManager;
        this.queueManager = queueManager;
        this.progressMapper = progressMapper;
        this.preloadService = preloadService;
    }

    // ===== 播放模式（按会话隔离） =====

    public PlayMode getCurrentMode(String sessionId) {
        return sessions.playbackSession(sessionId).getPlayMode();
    }

    public PlayMode switchMode(String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        PlayMode newMode = session.nextPlayMode();
        persistMode(sessionId, newMode);
        return newMode;
    }

    public PlayMode setMode(PlayMode mode, String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        session.setPlayMode(mode);
        persistMode(sessionId, mode);
        return mode;
    }

    // 获取当前播放歌曲
    public MusicFile getCurrentSong(String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        String songId = session.getCurrentSong();
        if (songId == null) {
            return null;
        }
        return scannerService.getMusicById(songId).orElse(null);
    }

    // 下一首
    public MusicFile next(String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        String beforeId = session.getCurrentSong();
        String nextId = session.getNextSong();
        if (nextId == null) {
            return null;
        }
        // 先解析曲库，确认真的能切过去，再提交状态（进度归零 / 落盘）。若队列里这个 ID
        // 已在曲库中消失（幽灵队列项），不归零进度、不落盘，直接返回 null，避免「切歌失败
        // 却把进度清零、位置已前进」的状态不一致。
        MusicFile song = scannerService.getMusicById(nextId).orElse(null);
        if (song == null) {
            log.warn("队列中歌曲已不存在于曲库，跳过切歌: " + nextId);
            return null;
        }
        session.onSongChanged(); // 切歌：进度归零、时长清零（保留 playing 标志以便连续播放）
        // 单曲循环（SINGLE）下 getNextSong 返回当前曲且下标不变：位置未真正变化，无需落盘
        if (!nextId.equals(beforeId)) {
            queueManager.persistQueue(sessionId); // 播放位置变了，落盘以便重启恢复
        }
        syncDuration(session, song);
        return song;
    }

    // 上一首
    public MusicFile previous(String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        String beforeId = session.getCurrentSong();
        String prevId = session.getPreviousSong();
        if (prevId == null) {
            return null;
        }
        MusicFile song = scannerService.getMusicById(prevId).orElse(null);
        if (song == null) {
            log.warn("队列中歌曲已不存在于曲库，跳过切歌: " + prevId);
            return null;
        }
        session.onSongChanged(); // 切歌：进度归零、时长清零（保留 playing 标志以便连续播放）
        // SHUFFLE 历史耗尽时 getPreviousSong 返回当前曲且下标不变（重播当前）：位置未变化，无需落盘
        if (!prevId.equals(beforeId)) {
            queueManager.persistQueue(sessionId); // 播放位置变了，落盘以便重启恢复
        }
        syncDuration(session, song);
        return song;
    }

    // ===== 播放进度控制 =====

    /**
     * 开始 / 恢复播放的核心逻辑（不含同步握手）。已暂停且进度 > 0 → 从暂停点恢复；
     * 否则（新加载 / 从头）从 0 开始。
     */
    private Map<String, Object> applyPlay(Integer durationOverride, String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        MusicFile current = getCurrentSong(sessionId);
        if (current == null) {
            return null;
        }
        int duration = (durationOverride != null && durationOverride > 0) ? durationOverride
                : (current.getMetadata() != null ? current.getMetadata().getDuration() : 0);
        // 已在播放则保持时钟不打断（/play 幂等）；已暂停且有进度则续播；否则从 0 开始
        session.startOrResumePlayback(duration);
        return progressMapper.buildProgress(sessionId, session, current);
    }

    /**
     * 开始 / 恢复播放（供 Web 客户端）。返回快照并在响应里加 {@code requireSync:true}，
     * 进入同步握手：客户端必须再回传 {@code /sync/ack} 才算真正同步。
     *
     * @param durationOverride 当前歌曲时长（秒）；为 {@code null} 时自动从元数据取
     * @param sessionId 会话 ID；为 {@code null} 时回退到起始会话（0 号）
     * @return 进度快照；队列为空时返回 {@code null}
     */
    public Map<String, Object> play(Integer durationOverride, String sessionId) {
        Map<String, Object> progress = applyPlay(durationOverride, sessionId);
        if (progress == null) {
            return null;
        }
        // 进入同步握手：服务端返回权威时间，但客户端必须再回传 /sync/ack 才算真正同步
        sessions.playbackSession(sessionId).noteClientSyncParticipation();
        progress.put("requireSync", true); // 提示客户端：已收到服务端权威时间，必须回传 /sync/ack
        return progress;
    }

    /**
     * 播放握手·阶段一：准备播放指定歌曲。
     *
     * <p>服务端把歌曲的一部分载入内存以加速后续流式播放，并定位到该曲（在队列中则移到该下标，
     * 否则追加到队尾——<strong>绝不</strong>清空或替换现有队列），但<strong>不启动</strong>权威
     * 播放时钟。时钟要等客户端显式调用 {@link #start(String)} 才开始，从而让客户端先确认「音乐已就绪」、
     * 再阻塞等待服务端告知「从何处开始」，避免进度条基线（0.00m）不一致。</p>
     *
     * @param musicId 目标歌曲 ID
     * @param sessionId 会话 ID；为 {@code null} 时回退到起始会话（0 号）
     * @return ready 快照（含 {@code status="ready"}、songId、duration 等）；歌曲不存在返回 {@code null}
     */
    public Map<String, Object> prepare(String musicId, String sessionId) {
        if (scannerService.getMusicById(musicId).isEmpty()) {
            return null;
        }
        PlaybackSession session = sessions.playbackSession(sessionId);
        // 在现有队列中定位到该曲；不在队列里则追加到队尾（修复「点曲库歌曲会清空整个队列」）。
        // 用 ensureSongPresent 把「检查 + 追加 + 定位」合成一次原子操作，避免并发下重复追加
        // 或 indexOf 定位错乱。
        int idx = session.ensureSongPresent(musicId);
        session.setCurrentIndex(idx);
        MusicFile song = getCurrentSong(sessionId);
        long dur = (song != null && song.getMetadata() != null) ? song.getMetadata().getDuration() : 0;
        session.prepareForPlayback(musicId, dur); // 准备（预缓冲意义上的准备；不启动时钟）
        queueManager.persistQueue(sessionId);
        // 预载入歌曲开头一部分到内存，加速客户端一旦开始播放时的首段流式。
        // 预缓冲失败不影响后续播放，退化为普通流式。
        try {
            preloadService.preload(musicId);
        } catch (Exception ignored) {
            // 预缓冲失败不影响后续播放，退化为普通流式
        }
        Map<String, Object> ready = new HashMap<>();
        ready.put("status", "ready");
        ready.put("songId", musicId);
        ready.put("title", song != null && song.getMetadata() != null ? song.getMetadata().getTitle() : "");
        ready.put("artist", song != null && song.getMetadata() != null ? song.getMetadata().getArtist() : "");
        ready.put("album", song != null && song.getMetadata() != null ? song.getMetadata().getAlbum() : "");
        ready.put("duration", (int) Math.round(dur));
        ready.put("isPreparing", true);
        return ready;
    }

    /**
     * 播放握手·阶段二：客户端在「音乐已就绪」后阻塞等待、问服务端「从何处开始」。
     * 服务端此时才启动权威播放时钟，并返回起始偏移与服务端权威时间戳
     * {@code serverTime}（epoch 毫秒）。客户端应以此时间戳作为进度条基线，
     * 而非本地 {@code audio.play()} 触发的时刻，从而避免基线漂移。
     *
     * @param sessionId 会话 ID；为 {@code null} 时回退到起始会话（0 号）
     * @return started 快照（含 {@code status="started"}、position 秒、serverTime 权威时间戳）；
     *         若尚未调用 {@link #prepare} 则返回 {@code null}
     */
    public Map<String, Object> start(String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        long startOffsetMillis = session.beginAuthoritativeClock();
        if (startOffsetMillis < 0) {
            return null; // 尚未 prepare
        }
        // 阶段二即代表客户端已与服务端对齐，视为一次同步确认（避免 5 分钟对账误报）
        session.markClientSynced();
        return buildStartedSnapshot(sessionId, session, startOffsetMillis);
    }

    /** 暂停播放，固化当前进度。队列为空时返回 {@code null}（与 {@link #play} 对齐）。
     * @param sessionId 会话 ID；为 {@code null} 时回退到起始会话（0 号） */
    public Map<String, Object> pause(String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        MusicFile current = getCurrentSong(sessionId);
        if (current == null) {
            return null;
        }
        session.pausePlayback();
        session.markClientSynced();
        return progressMapper.buildProgress(sessionId, session, current);
    }

    /**
     * 在现有播放队列中选播某首歌：若队列已包含该曲则直接定位，否则追加到队尾，
     * 不会替换或清空队列里的其它歌曲（修复「点曲库歌曲会把整个队列清空」的问题）。
     * 返回值为两段式握手的结果快照（{@code status="started"}、含权威 {@code serverTime}）；
     * 歌曲不存在时返回 {@code null}。Web 端也可改用显式的 {@link #prepare} + {@link #start}
     * 两段式以获得「先就绪、再等待开始」的体验。
     *
     * @param sessionId 会话 ID；为 {@code null} 时回退到起始会话（0 号）
     */
    public Map<String, Object> playSongById(String id, String sessionId) {
        if (scannerService.getMusicById(id).isEmpty()) {
            return null;
        }
        PlaybackSession session = sessions.playbackSession(sessionId);
        // 定位到该曲（在队列中则移到该下标，否则追加）——与 prepare 一致，绝不替换/清空队列
        int idx = session.ensureSongPresent(id);
        session.setCurrentIndex(idx);
        MusicFile song = resolveCurrent(session);
        long dur = (song != null && song.getMetadata() != null) ? song.getMetadata().getDuration() : 0;
        // 原子：准备 + 启动权威时钟。之前这里拆成 prepare() + start() 两次独立调用，
        // 并发两个 playSongById 时 A 的 start 可能启动 B 刚 prepare 的歌（会话级竞态）。
        long startOffsetMillis = session.prepareAndStart(id, dur);
        // 与 /start 对齐：一步到位即视为一次同步确认，避免 5 分钟对账误报
        session.markClientSynced();
        queueManager.persistQueue(sessionId);
        try {
            preloadService.preload(id);
        } catch (Exception ignored) {
            // 预缓冲失败不影响后续播放，退化为普通流式
        }
        return buildStartedSnapshot(sessionId, session, startOffsetMillis);
    }

    /**
     * 跳转到指定位置（秒）。
     *
     * @param positionSeconds 目标位置（秒，向下取整为 0）
     * @param sessionId 会话 ID；为 {@code null} 时回退到起始会话（0 号）
     */
    public Map<String, Object> seek(int positionSeconds, String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        MusicFile current = getCurrentSong(sessionId);
        if (current == null) {
            return null;
        }
        session.seekPlayback(Math.max(0, positionSeconds));
        session.markClientSynced();
        return progressMapper.buildProgress(sessionId, session, current);
    }

    /**
     * 组装「当前播放进度」快照，供前端进度条与「正在播放」展示使用。
     *
     * @param sessionId 会话 ID；为 {@code null} 时回退到起始会话（0 号）
     */
    public Map<String, Object> getProgress(String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        session.stopIfEnded(); // 歌曲已播完则自动停住，避免状态卡在 playing、守卫误报
        // 纯拉取：不视为同步确认。确认必须由客户端显式 POST /sync/ack 完成（见 play 的握手约定）
        MusicFile current = getCurrentSong(sessionId);
        // 空队列也统一走 buildProgress 组装，使 empty 快照与 ProgressMapper 内部形状一致
        // （含 sessionId / lastSyncedAt / syncDueAt），前端无需为多种 empty 形状分别处理。
        return progressMapper.buildProgress(sessionId, session, current);
    }

    /** 显式确认客户端已与服务端同步播放进度（供 {@code sync.ack} 命令调用）。
     * @param sessionId 会话 ID；为 {@code null} 时回退到起始会话（0 号） */
    public void markClientSynced(String sessionId) {
        sessions.playbackSession(sessionId).markClientSynced();
    }

    /** 从会话取当前歌曲 ID 并解析成曲库实体（队列为空或 ID 已失效时返回 {@code null}）。 */
    private MusicFile resolveCurrent(PlaybackSession session) {
        String id = session.getCurrentSong();
        return id != null ? scannerService.getMusicById(id).orElse(null) : null;
    }

    /** 组装两段式握手阶段二的 started 快照（含权威 {@code serverTime} 与起始偏移）。 */
    private Map<String, Object> buildStartedSnapshot(String sessionId, PlaybackSession session,
                                                     long startOffsetMillis) {
        MusicFile current = resolveCurrent(session);
        Map<String, Object> started = progressMapper.buildProgress(sessionId, session, current);
        started.put("status", "started");
        started.put("position", Math.round(startOffsetMillis / 100.0) / 100.0); // 起始偏移（秒）
        started.put("serverTime", session.getSegmentStartEpoch()); // 权威时间戳：客户端进度条基线
        started.put("isPlaying", true);
        return started;
    }

    /**
     * 把歌曲元数据里的总时长同步进会话（秒 → 毫秒）。
     */
    private void syncDuration(PlaybackSession session, MusicFile song) {
        int dur = song.getMetadata() != null ? song.getMetadata().getDuration() : 0;
        if (dur > 0) {
            session.setDurationSeconds(dur);
        }
    }

    /** 把当前播放模式写回配置文件，使其在重启后保留。仅起始会话（0 号）持久化。 */
    private void persistMode(String sessionId, PlayMode mode) {
        if (!sessions.isStartSession(sessionId)) {
            return;
        }
        configManager.savePlayMode(mode.name());
    }
}
