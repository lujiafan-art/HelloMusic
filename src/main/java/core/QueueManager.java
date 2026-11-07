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
import java.util.List;
import java.util.Map;

/**
 * 播放队列管理：加载 / 追加 / 清空 / 落盘。
 *
 * <p>原 {@link PlaybackService} 里的队列职责抽到本类。会话与播放状态从
 * {@link SessionRegistry} 取用；队列上限与「是否自动保存列表」均来自 {@link ConfigManager}；
 * 落盘也走 {@link ConfigManager}。切歌后需要落盘时，由 {@link PlaybackEngine} 回调
 * {@link #persistQueue(String)}。</p>
 */
public class QueueManager {

    private static final Logger log = LoggerFactory.getLogger(QueueManager.class);

    private final SessionRegistry sessions;
    private final MusicScannerService scannerService;
    private final PlaylistService playlistService;
    private final ConfigManager configManager;

    public QueueManager(SessionRegistry sessions,
                        MusicScannerService scannerService,
                        PlaylistService playlistService,
                        ConfigManager configManager) {
        this.sessions = sessions;
        this.scannerService = scannerService;
        this.playlistService = playlistService;
        this.configManager = configManager;
    }

    /**
     * 加载播放列表：只保留曲库中实际存在的歌曲，过滤掉失效 ID，避免产生「幽灵队列」
     * （队列里挂着曲库查不到的 ID，导致 /current 返回 empty）。行为与
     * {@link #loadPlaylistById} 对齐——后者在歌单层面做同样的过滤。
     *
     * @return 加载结果：实际加载数、因无效而跳过数、因超出队列上限而丢弃数，三者分别统计，
     *         便于前端区分「ID 无效」与「队列满」两种不同情况。
     */
    public LoadResult loadPlaylist(List<String> musicIds, String sessionId) {
        int requested = musicIds.size();
        List<String> existing = musicIds.stream()
                .filter(id -> id != null && scannerService.getMusicById(id).isPresent())
                .toList();
        int invalid = requested - existing.size();
        // 队列上限：超出部分直接丢弃，避免一次性把整库塞进内存
        int max = configManager.getMaxQueueSize();
        List<String> validIds = existing.size() > max ? existing.subList(0, max) : existing;
        int dropped = existing.size() - validIds.size();
        PlaybackSession session = sessions.playbackSession(sessionId);
        session.setPlaylist(validIds);
        persistQueue(sessionId);
        if (invalid > 0) {
            log.info("加载播放列表时跳过 " + invalid + " 首（曲库中不存在或 ID 为 null）");
        }
        if (dropped > 0) {
            log.info("加载播放列表时因队列上限 " + max + " 丢弃 " + dropped + " 首");
        }
        return new LoadResult(validIds.size(), invalid, dropped);
    }

    /** 一次加载的结果：实际加载数 / 无效 ID 数 / 因上限丢弃数。 */
    public record LoadResult(int loaded, int invalid, int dropped) {}

    // 加载全部音乐
    public void loadAllMusic(String sessionId) {
        List<MusicFile> all = scannerService.getAllMusic();
        List<String> ids = all.stream().map(MusicFile::getId).toList();
        loadPlaylist(ids, sessionId);
    }

    /**
     * 根据歌单 ID 把歌单加载到播放队列。
     * 会自动过滤掉已不在音乐库中的失效歌曲。
     *
     * @return 歌单不存在或过滤后为空时返回 {@code false}
     */
    public boolean loadPlaylistById(String playlistId, String sessionId) {
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
        loadPlaylist(validIds, sessionId);
        return true;
    }

    /** 向播放队列追加一首歌的结果：已新增 / 已在队列（去重命中）/ 队列已满。 */
    public enum AddResult {
        ADDED,
        ALREADY_PRESENT,
        QUEUE_FULL
    }

    /**
     * 添加歌曲到队列（去重 + 上限检查）。
     *
     * <p>「是否已在队列」「是否已达上限」与「追加」由 {@link PlaybackSession#addIfAbsent}
     * 合成一次原子操作完成，避免并发下重复添加或突破上限。</p>
     *
     * @return {@link AddResult#ADDED} 本次确实加入；{@link AddResult#ALREADY_PRESENT}
     *         该曲已在队列（去重跳过）；{@link AddResult#QUEUE_FULL} 队列已达上限。
     */
    public AddResult addToQueue(String musicId, String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        int max = configManager.getMaxQueueSize();
        AddResult result = session.addIfAbsent(musicId, max);
        if (result == AddResult.QUEUE_FULL) {
            log.warn("队列已达上限 " + max + " 首，忽略本次添加: " + musicId);
        } else if (result == AddResult.ADDED) {
            persistQueue(sessionId);
        }
        return result;
    }

    // 清空队列
    public void clearQueue(String sessionId) {
        sessions.playbackSession(sessionId).clear();
        persistQueue(sessionId);
    }

    // 获取播放队列信息
    public Map<String, Object> getQueueInfo(String sessionId) {
        PlaybackSession session = sessions.playbackSession(sessionId);
        String currentId = session.getCurrentSong();
        MusicFile current = currentId != null ? scannerService.getMusicById(currentId).orElse(null) : null;

        // 注意：这里必须用 HashMap，因为队列为空时 currentSong 为 null，
        // 而 Map.of 不允许 null 值，会抛 NullPointerException
        Map<String, Object> info = new HashMap<>();
        info.put("sessionId", sessionId);
        info.put("playMode", session.getPlayMode().getDisplayName());
        info.put("currentIndex", session.getCurrentIndex());
        info.put("total", session.getSize());
        info.put("currentSong", current);
        info.put("isEmpty", session.isEmpty());
        return info;
    }

    // 获取播放队列（仅ID列表）
    public List<String> getQueue(String sessionId) {
        return sessions.playbackSession(sessionId).getPlaylist();
    }

    /** 把当前播放队列与播放位置落盘，供重启后恢复（见 {@link ConfigManager#savePlaybackQueue}）。
     *  仅起始会话（0 号）持久化；前端独立会话为临时会话，不写盘。 */
    void persistQueue(String sessionId) {
        if (!sessions.isStartSession(sessionId)) {
            return; // 临时前端会话不持久化队列
        }
        // 「是否自动保存列表」= 0 时完全不落盘，重启后不恢复现场
        if (!configManager.isAutoSaveQueue()) {
            return;
        }
        PlaybackSession session = sessions.playbackSession();
        configManager.savePlaybackQueue(session.getPlaylist(), session.getCurrentIndex());
    }
}
