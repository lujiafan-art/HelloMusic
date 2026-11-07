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
 * 播放控制引擎：play / pause / seek / next / previous / 播放模式。
 *
 * <p>原 {@link PlaybackService} 里的播放控制职责抽到本类。会话状态取自
 * {@link SessionRegistry}，进度快照委托 {@link ProgressMapper} 组装，切歌 / 选播
 * 需要落盘队列时回调 {@link QueueManager#persistQueue(String)}。</p>
 *
 * <p><b>不持有时钟。</b>播放位置归客户端（浏览器用 audio.currentTime），本引擎不推算
 * 「现在播到第几秒」；{@code play}/{@code pause}/{@code seek} 只把歌曲时长作为元数据记录
 * （供 {@code progress} 展示），不做任何位置状态改动。</p>
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
        return advanceAndCommit(sessionId, true);
    }

    // 上一首
    public MusicFile previous(String sessionId) {
        return advanceAndCommit(sessionId, false);
    }

    /**
     * 前进（{@code forward=true}）或后退（{@code forward=false}）一步并提交状态。
     *
     * <p>游标推进与歌曲解析由 {@link PlaybackSession#advanceToNextResolved} /
     * {@link PlaybackSession#advanceToPreviousResolved} 一次完成：对每个候选 ID 只查一次曲库，
     * 命中即返回该曲的 {@link MusicFile}，途中遇到的「幽灵队列项」（曲库中已查不到的 ID）会被
     * 就地移除，因此游标<strong>绝不会</strong>停在失效项上。这也消除了旧实现「校验通过后、再
     * 重查一次曲库」的窄竞态——两次查询之间并发重扫可能把该曲移出，导致返回 null 却已推进游标。</p>
     *
     * @return 切到的歌曲；队列为空或全部为幽灵项时返回 {@code null}
     */
    private MusicFile advanceAndCommit(String sessionId, boolean forward) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        String beforeId = session.getCurrentSong();
        int sizeBefore = session.getSize();
        MusicFile song = forward ? session.advanceToNextResolved(this::resolve)
                                 : session.advanceToPreviousResolved(this::resolve);
        if (song == null) {
            return null; // 队列为空，或已全部被清理为幽灵项（此时队列已被清空）
        }
        boolean removedGhost = session.getSize() < sizeBefore; // 本次顺手清理了幽灵项
        boolean positionChanged = !song.getId().equals(beforeId);
        // 播放位置变了，或队列因清理幽灵项而缩短，都落盘以便重启恢复
        if (removedGhost || positionChanged) {
            queueManager.persistQueue(sessionId);
        }
        syncDuration(session, song);
        return song;
    }

    /** 解析某 ID 为曲库实体（查不到则返回 {@code null}）；供会话层一步完成「校验 + 解析」。 */
    private MusicFile resolve(String id) {
        return id != null ? scannerService.getMusicById(id).orElse(null) : null;
    }

    // ===== 播放进度控制 =====

    /**
     * 开始 / 恢复播放的核心逻辑。不启动任何时钟——仅把当前歌曲时长作为元数据记录，
     * 供 {@code progress} 展示。
     */
    private Map<String, Object> applyPlay(Integer durationOverride, String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        MusicFile current = getCurrentSong(sessionId);
        if (current == null) {
            return null;
        }
        int duration = (durationOverride != null && durationOverride > 0) ? durationOverride
                : (current.getMetadata() != null ? current.getMetadata().getDuration() : 0);
        session.setDurationSeconds(duration); // 仅记录元数据时长，不启动时钟
        return progressMapper.buildProgress(sessionId, session, current);
    }

    /**
     * 开始 / 恢复播放（供 Web 客户端）。返回当前歌曲元数据快照。位置归客户端，服务端不推算。
     *
     * @param durationOverride 当前歌曲时长（秒）；为 {@code null} 时自动从元数据取
     * @param sessionId 会话 ID；为 {@code null} 时回退到起始会话（0 号）
     * @return 进度快照；队列为空时返回 {@code null}
     */
    public Map<String, Object> play(Integer durationOverride, String sessionId) {
        return applyPlay(durationOverride, sessionId);
    }

    /**
     * 准备播放指定歌曲：服务端预载歌曲首段缓冲，并定位到该曲（在队列中则移到该下标，
     * 否则追加到队尾——<strong>绝不</strong>清空或替换现有队列）。返回 ready 快照。
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
        ready.put("title", song != null ? song.displayTitle() : "");
        ready.put("artist", song != null ? song.displayArtist() : "");
        ready.put("album", song != null && song.getMetadata() != null ? song.getMetadata().getAlbum() : "");
        ready.put("duration", (int) Math.round(dur));
        return ready;
    }

    /** 暂停播放。位置归客户端，服务端不持有时钟——此处仅返回当前歌曲元数据快照，不做状态改动。
     * 队列为空时返回 {@code null}（与 {@link #play} 对齐）。
     * @param sessionId 会话 ID；为 {@code null} 时回退到起始会话（0 号） */
    public Map<String, Object> pause(String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        MusicFile current = getCurrentSong(sessionId);
        if (current == null) {
            return null;
        }
        return progressMapper.buildProgress(sessionId, session, current);
    }

    /**
     * 在现有播放队列中选播某首歌：若队列已包含该曲则直接定位，否则追加到队尾，
     * 不会替换或清空队列里的其它歌曲（修复「点曲库歌曲会把整个队列清空」的问题）。
     * 返回当前歌曲元数据快照；歌曲不存在时返回 {@code null}。
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
        syncDuration(session, song); // 仅记录元数据时长
        queueManager.persistQueue(sessionId);
        try {
            preloadService.preload(id);
        } catch (Exception ignored) {
            // 预缓冲失败不影响后续播放，退化为普通流式
        }
        return progressMapper.buildProgress(sessionId, session, song);
    }

    /**
     * 跳转到指定位置（秒）。位置归客户端，服务端不持有时钟——此处不修改任何服务端状态，
     * 仅返回当前歌曲元数据快照。
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
        return progressMapper.buildProgress(sessionId, session, current);
    }

    /**
     * 组装「当前播放进度」快照，供前端展示。仅含歌曲元数据（不含实时位置），
     * 因为位置归客户端（浏览器用 audio.currentTime）。
     *
     * @param sessionId 会话 ID；为 {@code null} 时回退到起始会话（0 号）
     */
    public Map<String, Object> getProgress(String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        MusicFile current = getCurrentSong(sessionId);
        return progressMapper.buildProgress(sessionId, session, current);
    }

    /** 从会话取当前歌曲 ID 并解析成曲库实体（队列为空或 ID 已失效时返回 {@code null}）。 */
    private MusicFile resolveCurrent(PlaybackSession session) {
        String id = session.getCurrentSong();
        return id != null ? scannerService.getMusicById(id).orElse(null) : null;
    }

    /**
     * 把歌曲元数据里的总时长同步进会话（秒 → 毫秒），仅作元数据供展示。
     */
    private void syncDuration(PlaybackSession session, MusicFile song) {
        int dur = song != null && song.getMetadata() != null ? song.getMetadata().getDuration() : 0;
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
