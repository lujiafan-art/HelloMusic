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

import java.util.List;
import java.util.Map;

/**
 * 播放门面：对外保持稳定的一套播放接口，内部按职责委托给三个协作组件。
 *
 * <p>原 {@link PlaybackService} 是一个近 700 行的上帝类，会话治理、播放控制、队列管理、
 * 进度组装揉在一起。现拆分为：</p>
 *
 * <ul>
 *   <li>{@link SessionRegistry}——会话生命周期（连接 / 心跳 / 断开 / 巡检）；</li>
 *   <li>{@link PlaybackEngine}——播放控制（play / pause / seek / next / previous）；</li>
 *   <li>{@link QueueManager}——队列加载 / 追加 / 清空 / 落盘；</li>
 *   <li>{@link ProgressMapper}——进度快照组装（被 {@link PlaybackEngine} 内部使用）。</li>
 * </ul>
 *
 * <p>本类不再持有任何业务状态，只做转发，因此命令分发器无需感知内部拆分。</p>
 */
public class PlaybackService {

    private final SessionRegistry sessionRegistry;
    private final PlaybackEngine playbackEngine;
    private final QueueManager queueManager;

    public PlaybackService(SessionRegistry sessionRegistry,
                           PlaybackEngine playbackEngine,
                           QueueManager queueManager) {
        this.sessionRegistry = sessionRegistry;
        this.playbackEngine = playbackEngine;
        this.queueManager = queueManager;
    }

    // ===== 会话管理（委托 SessionRegistry） =====

    public Map<String, Object> connect(String clientIp, String remoteAddr) {
        return sessionRegistry.connect(clientIp, remoteAddr);
    }

    public boolean heartbeat(String sessionId) {
        return sessionRegistry.heartbeat(sessionId);
    }

    public boolean disconnect(String sessionId) {
        return sessionRegistry.disconnect(sessionId);
    }

    public Map<String, Object> sessionStatus(String sessionId) {
        return sessionRegistry.sessionStatus(sessionId);
    }

    /** 会话巡检（定时调度在 {@link SessionRegistry#inspectSessions()} 上，此处仅转发）。 */
    public void inspectSessions() {
        sessionRegistry.inspectSessions();
    }

    // ===== 播放控制（委托 PlaybackEngine） =====

    public PlayMode getCurrentMode(String sessionId) {
        return playbackEngine.getCurrentMode(sessionId);
    }

    public PlayMode switchMode(String sessionId) {
        return playbackEngine.switchMode(sessionId);
    }

    public PlayMode setMode(PlayMode mode, String sessionId) {
        return playbackEngine.setMode(mode, sessionId);
    }

    public MusicFile getCurrentSong(String sessionId) {
        return playbackEngine.getCurrentSong(sessionId);
    }

    public MusicFile next(String sessionId) {
        return playbackEngine.next(sessionId);
    }

    public MusicFile previous(String sessionId) {
        return playbackEngine.previous(sessionId);
    }

    public Map<String, Object> play(Integer durationOverride, String sessionId) {
        return playbackEngine.play(durationOverride, sessionId);
    }

    public Map<String, Object> prepare(String musicId, String sessionId) {
        return playbackEngine.prepare(musicId, sessionId);
    }

    public Map<String, Object> playSongById(String id, String sessionId) {
        return playbackEngine.playSongById(id, sessionId);
    }

    public Map<String, Object> pause(String sessionId) {
        return playbackEngine.pause(sessionId);
    }

    public Map<String, Object> seek(int positionSeconds, String sessionId) {
        return playbackEngine.seek(positionSeconds, sessionId);
    }

    public Map<String, Object> getProgress(String sessionId) {
        return playbackEngine.getProgress(sessionId);
    }

    // ===== 队列管理（委托 QueueManager） =====

    public QueueManager.LoadResult loadPlaylist(List<String> musicIds, String sessionId) {
        return queueManager.loadPlaylist(musicIds, sessionId);
    }

    public void loadAllMusic(String sessionId) {
        queueManager.loadAllMusic(sessionId);
    }

    public boolean loadPlaylistById(String playlistId, String sessionId) {
        return queueManager.loadPlaylistById(playlistId, sessionId);
    }

    public QueueManager.AddResult addToQueue(String musicId, String sessionId) {
        return queueManager.addToQueue(musicId, sessionId);
    }

    public void clearQueue(String sessionId) {
        queueManager.clearQueue(sessionId);
    }

    public Map<String, Object> getQueueInfo(String sessionId) {
        return queueManager.getQueueInfo(sessionId);
    }

    public List<String> getQueue(String sessionId) {
        return queueManager.getQueue(sessionId);
    }
}
