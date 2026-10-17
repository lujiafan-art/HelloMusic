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

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;

/**
 * 单个播放会话的状态：播放队列、当前下标与播放模式。
 *
 * <p><b>线程安全：</b>会话会被 CLI 线程与多个 HTTP 请求线程共享（{@code PlaybackService}
 * 只有一个默认会话），因此这里所有公开方法都加了 {@code synchronized}，把「读下标 → 计算
 * → 写下标」这类复合操作变成原子操作，避免并发调用 {@code next()/previous()/loadPlaylist()}
 * 时出现下标错乱、重复播放或 {@code IndexOutOfBoundsException}。
 * {@code playlist} 是普通 {@code ArrayList}，不允许在未持锁的情况下从外部遍历。</p>
 */
public class PlaybackSession {
    private final List<String> playlist;
    private int currentIndex;
    private PlayMode playMode;
    private final Random random;
    /** SHUFFLE 模式下已播放下标的回溯栈，使「上一首」真正可用。仅在 SHUFFLE 路径上写入与读取。 */
    private final ArrayDeque<Integer> shuffleHistory = new ArrayDeque<>();

    // ===== 播放进度时钟（服务端权威的「当前播放到第几秒」） =====
    /** 当前是否处于播放状态。 */
    private boolean playing = false;
    /** 当前播放片段开始前已累计的播放进度（毫秒）。 */
    private long positionBaseMillis = 0;
    /** 当前播放片段开始的 epoch 毫秒；仅在 {@code playing} 时有效。 */
    private long segmentStartEpoch = 0;
    /** 当前歌曲总时长（毫秒）；0 表示未知。 */
    private long durationMillis = 0;
    /** 客户端最后确认同步播放进度的 epoch 毫秒；0 表示从未同步（活跃播放守卫用）。 */
    private long lastClientSyncEpochMillis = 0;
    /** 是否有客户端参与过同步握手（调用过 /play、/pause、/seek 或 /sync/ack）。用于区分「纯 CLI 播放」与「应当同步的客户端」。 */
    private boolean clientSyncParticipating = false;

    public PlaybackSession() {
        this.playlist = new ArrayList<>();
        this.currentIndex = 0;
        this.playMode = PlayMode.FORWARD;
        this.random = new Random();
    }

    public synchronized void setPlaylist(List<String> musicIds) {
        this.playlist.clear();
        this.playlist.addAll(musicIds);
        this.currentIndex = 0;
        this.shuffleHistory.clear();
        resetProgress();
    }

    public synchronized void addSong(String musicId) {
        this.playlist.add(musicId);
    }

    public synchronized void clear() {
        this.playlist.clear();
        this.currentIndex = 0;
        this.shuffleHistory.clear();
        resetProgress();
    }

    public synchronized String getCurrentSong() {
        if (playlist.isEmpty()) {
            return null;
        }
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            return null;
        }
        return playlist.get(currentIndex);
    }

    public synchronized String getNextSong() {
        if (playlist.isEmpty()) {
            return null;
        }

        switch (playMode) {
            case SINGLE:
                return getCurrentSong();

            case SHUFFLE: {
                if (playlist.size() == 1) {
                    return playlist.get(0);
                }
                int randomIndex;
                do {
                    randomIndex = random.nextInt(playlist.size());
                } while (randomIndex == currentIndex);
                // 记录「当前」以便上一首回溯（上面 do-while 已保证不会立即重复）
                shuffleHistory.addLast(currentIndex);
                if (shuffleHistory.size() > 1000) {
                    shuffleHistory.removeFirst(); // 限制历史长度，避免长时间播放无限增长
                }
                currentIndex = randomIndex;
                return playlist.get(currentIndex);
            }

            case REVERSE:
                if (currentIndex <= 0) {
                    currentIndex = playlist.size() - 1;
                } else {
                    currentIndex--;
                }
                return playlist.get(currentIndex);

            case FORWARD:
            default:
                if (currentIndex >= playlist.size() - 1) {
                    currentIndex = 0;
                } else {
                    currentIndex++;
                }
                return playlist.get(currentIndex);
        }
    }

    public synchronized String getPreviousSong() {
        if (playlist.isEmpty()) {
            return null;
        }

        switch (playMode) {
            case SINGLE:
                return getCurrentSong();

            case SHUFFLE: {
                // 回溯到上一首随机播放的歌曲；没有历史时重播当前（与原行为一致）
                Integer prev = shuffleHistory.pollLast();
                if (prev == null) {
                    return getCurrentSong();
                }
                currentIndex = prev;
                return playlist.get(currentIndex);
            }

            case REVERSE:
                if (currentIndex >= playlist.size() - 1) {
                    currentIndex = 0;
                } else {
                    currentIndex++;
                }
                return playlist.get(currentIndex);

            case FORWARD:
            default:
                if (currentIndex <= 0) {
                    currentIndex = playlist.size() - 1;
                } else {
                    currentIndex--;
                }
                return playlist.get(currentIndex);
        }
    }

    public synchronized int getCurrentIndex() {
        return currentIndex;
    }

    public synchronized int getSize() {
        return playlist.size();
    }

    public synchronized boolean isEmpty() {
        return playlist.isEmpty();
    }

    public synchronized PlayMode getPlayMode() {
        return playMode;
    }

    public synchronized void setPlayMode(PlayMode playMode) {
        this.playMode = playMode;
    }

    public synchronized PlayMode nextPlayMode() {
        PlayMode[] modes = PlayMode.values();
        int nextOrdinal = (playMode.ordinal() + 1) % modes.length;
        this.playMode = modes[nextOrdinal];
        return this.playMode;
    }

    public synchronized List<String> getPlaylist() {
        return new ArrayList<>(playlist);
    }

    // ===== 播放进度时钟方法 =====

    /**
     * 开始（或恢复）播放，并重置计时基准。
     *
     * @param durationSeconds 当前歌曲总时长（秒）；≤0 表示未知
     */
    public synchronized void startPlayback(long durationSeconds) {
        this.durationMillis = Math.max(0, durationSeconds) * 1000L;
        this.positionBaseMillis = 0;
        this.segmentStartEpoch = System.currentTimeMillis();
        this.playing = true;
    }

    /**
     * 从「已暂停」状态恢复播放：保留已固化的播放进度（{@code positionBaseMillis}），
     * 仅重启计时基准，使播放从暂停处续播，而不是从头开始。
     */
    public synchronized void resumePlayback() {
        this.segmentStartEpoch = System.currentTimeMillis();
        this.playing = true;
    }

    /** 暂停：把已播放时长固化到 {@code positionBaseMillis}，停止时钟推进。 */
    public synchronized void pausePlayback() {
        if (playing) {
            positionBaseMillis += System.currentTimeMillis() - segmentStartEpoch;
            playing = false;
        }
    }

    /**
     * 跳转到指定位置（秒）。保持播放状态不变；若正在播放则重启计时基准，
     * 使「从跳转点开始」继续走表。
     */
    public synchronized void seekPlayback(long positionSeconds) {
        this.positionBaseMillis = Math.max(0, positionSeconds) * 1000L;
        if (playing) {
            this.segmentStartEpoch = System.currentTimeMillis();
        }
    }

    /** 清空当前进度（切歌前调用）。保留 {@code playing} 标志，使连续播放能自动续表。 */
    public synchronized void onSongChanged() {
        this.positionBaseMillis = 0;
        this.durationMillis = 0;
        this.segmentStartEpoch = playing ? System.currentTimeMillis() : 0;
    }

    /** 若当前正在播放且已到达歌曲末尾，则自动停止（固化进度到末尾），使状态自洽，避免守卫误报。 */
    public synchronized void stopIfEnded() {
        if (playing && durationMillis > 0 && getPositionMillis() >= durationMillis) {
            positionBaseMillis = durationMillis;
            playing = false;
        }
    }

    /** 彻底重置进度与播放标志（加载新列表 / 清空队列时调用）。 */
    public synchronized void resetProgress() {
        this.playing = false;
        this.positionBaseMillis = 0;
        this.segmentStartEpoch = 0;
        this.durationMillis = 0;
    }

    /** 当前已播放毫秒数（受时长上限收敛，不会超过歌曲总时长）。 */
    public synchronized long getPositionMillis() {
        long pos = positionBaseMillis;
        if (playing) {
            pos += System.currentTimeMillis() - segmentStartEpoch;
        }
        if (durationMillis > 0 && pos > durationMillis) {
            pos = durationMillis;
        }
        return Math.max(0, pos);
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    public synchronized long getDurationMillis() {
        return durationMillis;
    }

    /** 仅写入当前歌曲总时长（秒 → 毫秒），不影响进度与播放标志。 */
    public synchronized void setDurationSeconds(long durationSeconds) {
        this.durationMillis = Math.max(0, durationSeconds) * 1000L;
    }

    /**
     * 记录客户端刚刚完成一次进度同步（显式 {@code /sync/ack}，或 {@code /pause}/{@code /seek}
     * 这类由客户端发起的状态推送）。服务端借此判断「客户端是否仍在跟随播放进度」。
     */
    public synchronized void markClientSynced() {
        this.lastClientSyncEpochMillis = System.currentTimeMillis();
        this.clientSyncParticipating = true;
    }

    /**
     * 标记有客户端参与了同步握手（例如收到 {@code /play} 时调用）。注意：参与握手
     * <strong>不等于</strong>已同步——客户端必须再回传 {@code /sync/ack} 才能算真正同步，
     * 否则守卫会在 5 分钟后记日志。
     */
    public synchronized void noteClientSyncParticipation() {
        this.clientSyncParticipating = true;
    }

    /** 是否曾有客户端参与过同步握手（纯 CLI 播放不会触发，因此不记日志）。 */
    public synchronized boolean hasClientSyncParticipation() {
        return clientSyncParticipating;
    }

    /** 客户端最后同步时间（epoch 毫秒）；0 表示从未同步。 */
    public synchronized long getLastClientSyncEpochMillis() {
        return lastClientSyncEpochMillis;
    }
}
