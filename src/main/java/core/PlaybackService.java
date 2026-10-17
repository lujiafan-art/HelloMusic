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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
public class PlaybackService {

    private static final Logger log = Logger.getLogger(PlaybackService.class.getName());
    /** 客户端必须至少每隔该时长同步一次播放进度，否则服务端记录日志。 */
    private static final long CLIENT_SYNC_INTERVAL_MS = 5 * 60 * 1000;

    private final MusicScannerService scannerService;
    private final PlaylistService playlistService;
    private final ConfigManager configManager;
    // 支持多个播放会话（可扩展为多用户）
    private final Map<String, PlaybackSession> sessions;

    @Autowired
    public PlaybackService(MusicScannerService scannerService,
                           PlaylistService playlistService,
                           ConfigManager configManager) {
        this.scannerService = scannerService;
        this.playlistService = playlistService;
        this.configManager = configManager;
        this.sessions = new ConcurrentHashMap<>();

        // 默认会话：恢复上次保存的播放模式，实现配置持久化
        PlaybackSession defaultSession = new PlaybackSession();
        defaultSession.setPlayMode(parseMode(configManager.getLastPlayMode()));
        this.sessions.put("default", defaultSession);
    }

    // 获取当前播放模式
    public PlayMode getCurrentMode() {
        return getSession().getPlayMode();
    }

    // 切换播放模式
    public PlayMode switchMode() {
        PlaybackSession session = getSession();
        PlayMode newMode = session.nextPlayMode();
        persistMode(newMode);
        System.out.println("🔄 切换播放模式: " + newMode.getDisplayName());
        return newMode;
    }

    // 设置播放模式
    public PlayMode setMode(PlayMode mode) {
        PlaybackSession session = getSession();
        session.setPlayMode(mode);
        persistMode(mode);
        System.out.println("🔄 设置播放模式: " + mode.getDisplayName());
        return mode;
    }

    // 获取当前播放歌曲
    public MusicFile getCurrentSong() {
        PlaybackSession session = getSession();
        String songId = session.getCurrentSong();
        if (songId == null) {
            return null;
        }
        return scannerService.getMusicById(songId).orElse(null);
    }

    // 下一首
    public MusicFile next() {
        PlaybackSession session = getSession();
        String nextId = session.getNextSong();
        if (nextId == null) {
            return null;
        }
        session.onSongChanged(); // 切歌：进度归零、时长清零（保留 playing 标志以便连续播放）
        MusicFile song = scannerService.getMusicById(nextId).orElse(null);
        if (song != null) {
            syncDuration(session, song);
            System.out.println("▶ 播放下一首: " + getDisplayName(song));
        }
        return song;
    }

    // 上一首
    public MusicFile previous() {
        PlaybackSession session = getSession();
        String prevId = session.getPreviousSong();
        if (prevId == null) {
            return null;
        }
        session.onSongChanged(); // 切歌：进度归零、时长清零（保留 playing 标志以便连续播放）
        MusicFile song = scannerService.getMusicById(prevId).orElse(null);
        if (song != null) {
            syncDuration(session, song);
            System.out.println("⏮ 播放上一首: " + getDisplayName(song));
        }
        return song;
    }

    /**
     * 加载播放列表：只保留曲库中实际存在的歌曲，过滤掉失效 ID，避免产生「幽灵队列」
     * （队列里挂着曲库查不到的 ID，导致 /current 返回 empty）。行为与
     * {@link #loadPlaylistById} 对齐——后者在歌单层面做同样的过滤。
     *
     * @return 实际有效加载的歌曲数（不存在或 {@code null} 的 ID 会被自动跳过）
     */
    public int loadPlaylist(List<String> musicIds) {
        List<String> validIds = musicIds.stream()
                .filter(id -> id != null && scannerService.getMusicById(id).isPresent())
                .toList();
        PlaybackSession session = getSession();
        session.setPlaylist(validIds);
        int skipped = musicIds.size() - validIds.size();
        System.out.println("📋 加载播放列表: " + validIds.size() + " 首歌曲"
                + (skipped > 0 ? "（已跳过 " + skipped + " 个不存在的 ID）" : ""));
        return validIds.size();
    }

    // 加载全部音乐
    public void loadAllMusic() {
        List<MusicFile> all = scannerService.getAllMusic();
        List<String> ids = all.stream().map(MusicFile::getId).toList();
        loadPlaylist(ids);
    }

    /**
     * 根据歌单 ID 把歌单加载到播放队列。
     * 会自动过滤掉已不在音乐库中的失效歌曲。
     *
     * @return 歌单不存在或过滤后为空时返回 {@code false}
     */
    public boolean loadPlaylistById(String playlistId) {
        Playlist playlist = playlistService.getPlaylist(playlistId);
        if (playlist == null) {
            return false;
        }
        List<String> validIds = playlist.getMusicIds().stream()
                .filter(id -> scannerService.getMusicById(id).isPresent())
                .toList();
        if (validIds.isEmpty()) {
            return false;
        }
        loadPlaylist(validIds);
        return true;
    }

    // 添加歌曲到队列
    public void addToQueue(String musicId) {
        getSession().addSong(musicId);
    }

    // 清空队列
    public void clearQueue() {
        getSession().clear();
    }

    // 获取播放队列信息
    public Map<String, Object> getQueueInfo() {
        PlaybackSession session = getSession();
        String currentId = session.getCurrentSong();
        MusicFile current = currentId != null ? scannerService.getMusicById(currentId).orElse(null) : null;

        // 注意：这里必须用 HashMap，因为队列为空时 currentSong 为 null，
        // 而 Map.of 不允许 null 值，会抛 NullPointerException
        Map<String, Object> info = new HashMap<>();
        info.put("playMode", session.getPlayMode().getDisplayName());
        info.put("currentIndex", session.getCurrentIndex());
        info.put("total", session.getSize());
        info.put("currentSong", current);
        info.put("isEmpty", session.isEmpty());
        return info;
    }

    // 获取播放队列（仅ID列表）
    public List<String> getQueue() {
        return getSession().getPlaylist();
    }

    // ===== 播放进度控制 =====

    /**
     * 开始 / 恢复播放。若未显式传入时长，则从当前歌曲元数据里读取。
     *
     * @param durationOverride 当前歌曲时长（秒）；为 {@code null} 时自动从元数据取
     * @return 进度快照；队列为空时返回 {@code null}
     */
    public Map<String, Object> play(Integer durationOverride) {
        PlaybackSession session = getSession();
        MusicFile current = getCurrentSong();
        if (current == null) {
            return null;
        }
        int duration = durationOverride != null ? durationOverride
                : (current.getMetadata() != null ? current.getMetadata().getDuration() : 0);
        // 已暂停且进度 > 0 → 从暂停点恢复；否则（新加载 / 从头）从 0 开始
        if (!session.isPlaying() && session.getPositionMillis() > 0) {
            session.resumePlayback();
        } else {
            session.startPlayback(duration);
        }
        // 进入同步握手：服务端返回权威时间，但客户端必须再回传 /sync/ack 才算真正同步
        session.noteClientSyncParticipation();
        Map<String, Object> progress = buildProgress(session, current);
        progress.put("requireSync", true); // 提示客户端：已收到服务端权威时间，必须回传 /sync/ack
        return progress;
    }

    /** 暂停播放，固化当前进度。 */
    public Map<String, Object> pause() {
        PlaybackSession session = getSession();
        session.pausePlayback();
        session.markClientSynced();
        return buildProgress(session, getCurrentSong());
    }

    /**
     * 跳转到指定位置（秒）。
     *
     * @param positionSeconds 目标位置（秒，向下取整为 0）
     */
    public Map<String, Object> seek(int positionSeconds) {
        PlaybackSession session = getSession();
        session.seekPlayback(Math.max(0, positionSeconds));
        session.markClientSynced();
        return buildProgress(session, getCurrentSong());
    }

    /**
     * 组装「当前播放进度」快照，供前端进度条与「正在播放」展示使用。
     */
    public Map<String, Object> getProgress() {
        PlaybackSession session = getSession();
        session.stopIfEnded(); // 歌曲已播完则自动停住，避免状态卡在 playing、守卫误报
        // 纯拉取：不视为同步确认。确认必须由客户端显式 POST /sync/ack 完成（见 play 的握手约定）
        MusicFile current = getCurrentSong();
        if (current == null) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("status", "empty");
            empty.put("lastSyncedAt", session.getLastClientSyncEpochMillis());
            empty.put("syncDueAt", session.getLastClientSyncEpochMillis() + CLIENT_SYNC_INTERVAL_MS);
            return empty;
        }
        return buildProgress(session, current);
    }

    /** 显式确认客户端已与服务端同步播放进度（供 {@code POST /api/playback/sync/ack} 调用）。 */
    public void markClientSynced() {
        getSession().markClientSynced();
    }

    /**
     * 把会话的播放状态与歌曲元数据拼成前端可用的进度快照。
     */
    private Map<String, Object> buildProgress(PlaybackSession session, MusicFile current) {
        Map<String, Object> progress = new HashMap<>();
        if (current == null) {
            progress.put("status", "empty");
            return progress;
        }

        long durationMillis = session.getDurationMillis();
        long positionMillis = session.getPositionMillis();
        double durationSeconds = durationMillis / 1000.0;
        double positionSeconds = positionMillis / 1000.0;
        boolean ended = durationMillis > 0 && positionMillis >= durationMillis;
        String status = ended ? "ended" : (session.isPlaying() ? "playing" : "paused");

        MusicMetadata meta = current.getMetadata();
        progress.put("status", status);
        progress.put("songId", current.getId());
        progress.put("title", meta != null ? meta.getTitle() : current.getFileName());
        progress.put("artist", meta != null ? meta.getArtist() : "");
        progress.put("album", meta != null ? meta.getAlbum() : "");
        progress.put("position", Math.round(positionSeconds * 10) / 10.0); // 保留 1 位小数
        progress.put("duration", (int) Math.round(durationSeconds));
        progress.put("isPlaying", session.isPlaying());
        progress.put("percent", durationMillis > 0
                ? Math.min(100.0, positionMillis * 100.0 / durationMillis)
                : 0.0);
        progress.put("playMode", session.getPlayMode().getDisplayName());
        // 同步对账信息：客户端应在此窗口内再来同步一次，否则服务端记日志
        progress.put("lastSyncedAt", session.getLastClientSyncEpochMillis());
        progress.put("syncDueAt", session.getLastClientSyncEpochMillis() + CLIENT_SYNC_INTERVAL_MS);
        return progress;
    }

    /**
     * 每 5 分钟运行一次：若某个播放会话处于<strong>活跃播放</strong>状态、但客户端已超过
     * {@link #CLIENT_SYNC_INTERVAL_MS} 未与服务端同步播放进度，则记录 WARN 日志。
     *
     * <p>服务端<strong>不会主动推送</strong>进度；此处仅做对账——客户端必须周期性来拉取
     * {@code /progress} 后显式 {@code POST /sync/ack} 以示「仍在同步」。已在 {@code /play}
     * 后参与同步握手、却始终不回传 {@code /sync/ack} 的客户端也会被记为未同步。</p>
     *
     * <p>仅在<strong>活跃播放</strong>时追责：已暂停的会话已通过 {@code /pause} 告知服务端、
     * 进度被固化，无需持续对账；纯 CLI 播放（从未有客户端参与握手）也不记日志，避免刷屏。</p>
     */
    @Scheduled(fixedRate = CLIENT_SYNC_INTERVAL_MS)
    public void checkClientSync() {
        long now = System.currentTimeMillis();
        for (PlaybackSession session : sessions.values()) {
            session.stopIfEnded(); // 先结算「已播完」的会话，避免守卫对其误报未同步
            if (session.isEmpty()) continue;
            if (!session.isPlaying()) continue; // 已暂停的会话无需持续对账
            if (!session.hasClientSyncParticipation()) continue; // 纯 CLI 播放等，未参与握手，不记日志
            long last = session.getLastClientSyncEpochMillis();
            if (now - last > CLIENT_SYNC_INTERVAL_MS) {
                log.warning("[播放同步] 客户端已超过 "
                        + (CLIENT_SYNC_INTERVAL_MS / 60000)
                        + " 分钟未与服务端同步播放进度（最后同步: "
                        + Instant.ofEpochMilli(last) + "），会话: default");
            }
        }
    }

    /** 把歌曲元数据里的总时长同步进会话（秒 → 毫秒）。 */
    private void syncDuration(PlaybackSession session, MusicFile song) {
        int dur = song.getMetadata() != null ? song.getMetadata().getDuration() : 0;
        if (dur > 0) {
            session.setDurationSeconds(dur);
        }
    }

    private PlaybackSession getSession() {
        // computeIfAbsent 保证取到的会话一定被存入 map，不会返回游离对象
        return sessions.computeIfAbsent("default", key -> new PlaybackSession());
    }

    /** 把当前播放模式写回配置文件，使其在重启后保留。 */
    private void persistMode(PlayMode mode) {
        configManager.savePlayMode(mode.name());
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

    private String getDisplayName(MusicFile song) {
        if (song == null) {
            return "未知";
        }
        if (song.getMetadata() != null && song.getMetadata().getTitle() != null) {
            return song.getMetadata().getTitle();
        }
        return song.getFileName();
    }
}
