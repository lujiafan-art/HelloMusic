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

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;

/**
 * 单个播放会话的状态：播放队列、当前下标与播放模式。
 *
 * <p><b>线程安全：</b>会话会被多个 HTTP 请求线程共享，因此这里所有公开方法都加了
 * {@code synchronized}，把「读下标 → 计算
 * → 写下标」这类复合操作变成原子操作，避免并发调用 {@code next()/previous()/loadPlaylist()}
 * 时出现下标错乱、重复播放或 {@code IndexOutOfBoundsException}。
 * {@code playlist} 是普通 {@code ArrayList}，不允许在未持锁的情况下从外部遍历。</p>
 */
public class PlaybackSession {
    private final List<String> playlist;
    private int currentIndex;
    private PlayMode playMode;
    private final Random rng;
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
    /** 是否有客户端参与过同步握手（调用过 /play、/pause、/seek 或 /sync/ack）。用于区分「尚未参与握手」与「应当同步的客户端」。 */
    private boolean clientSyncParticipating = false;
    /** 是否已通过 {@link #prepareForPlayback} 准备好要播放的歌曲（预缓冲完成、但权威时钟尚未启动）。 */
    private boolean prepared = false;
    /** 准备阶段对应的歌曲 ID，用于「同一首歌续播」与「切歌从头」的判定。 */
    private String preparedSongId = null;

    public PlaybackSession() {
        this.playlist = new ArrayList<>();
        this.currentIndex = 0;
        this.playMode = PlayMode.FORWARD;
        this.rng = new Random();
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

    /**
     * 原子地「确保歌曲在队列中，并返回其下标」。
     *
     * <p>把「检查是否已在 → 不在则追加 → 定位下标」三步合成一次同步操作，供
     * {@code prepare} 在并发下安全调用，避免「contains 与 indexOf 针对不同快照」造成的
     * 重复追加或 {@code indexOf == -1} 兜底重复。</p>
     *
     * @return 歌曲在队列中的下标（含本次追加）
     */
    public synchronized int ensureSongPresent(String musicId) {
        int idx = playlist.indexOf(musicId);
        if (idx < 0) {
            playlist.add(musicId);
            idx = playlist.size() - 1;
        }
        return idx;
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
                    randomIndex = rng.nextInt(playlist.size());
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

    /**
     * 恢复播放位置（队列下标）。仅当队列非空且下标合法时才生效，避免持久化数据损坏时越界。
     */
    public synchronized void setCurrentIndex(int index) {
        if (!playlist.isEmpty() && index >= 0 && index < playlist.size()) {
            this.currentIndex = index;
        }
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
        if (this.playMode != playMode) {
            // 模式真正发生变化时清空随机回溯栈：SHUFFLE 的历史只在该模式下有意义，
            // 切走再切回若不清空，会残留旧会话的随机下标，导致「上一首」跳到无关歌曲。
            this.shuffleHistory.clear();
        }
        this.playMode = playMode;
    }

    public synchronized PlayMode nextPlayMode() {
        PlayMode[] modes = PlayMode.values();
        int nextOrdinal = (playMode.ordinal() + 1) % modes.length;
        this.playMode = modes[nextOrdinal];
        this.shuffleHistory.clear(); // 切模式必然离开当前上下文，旧随机历史作废
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
    private synchronized void startPlayback(long durationSeconds) {
        this.durationMillis = Math.max(0, durationSeconds) * 1000L;
        this.positionBaseMillis = 0;
        this.segmentStartEpoch = System.currentTimeMillis();
        this.playing = true;
        // /play 走的是「直接启动」这条时钟路径，与 /prepare + /start 的两段式握手互斥：
        // 一旦经此处启动，任何先前残留的「已准备」状态都应作废，避免 isPrepared() 仍为 true、
        // 与「实际已通过另一套 API 开播」矛盾（两套 API 混用时的状态不一致）。
        clearPrepared();
    }

    /**
     * 从「已暂停」状态恢复播放：保留已固化的播放进度（{@code positionBaseMillis}），
     * 仅重启计时基准，使播放从暂停处续播，而不是从头开始。
     */
    private synchronized void resumePlayback() {
        this.segmentStartEpoch = System.currentTimeMillis();
        this.playing = true;
        clearPrepared(); // 同上：/play 路径启动时钟即消费「已准备」状态
    }

    /** 清除「已准备」状态（两段式握手在时钟启动前的临时态）。 */
    private void clearPrepared() {
        this.prepared = false;
        this.preparedSongId = null;
    }

    /**
     * 开始 / 恢复播放的统一入口（幂等）。
     * <ul>
     *   <li>已在播放：保持当前时钟，直接返回，不重启（修复「播放中再次触发 /play 会从头重放」）。</li>
     *   <li>已暂停且进度 &gt; 0：从暂停点恢复（{@link #resumePlayback}）。</li>
     *   <li>否则（新加载 / 从头）：从 0 秒开始（{@link #startPlayback}）。</li>
     * </ul>
     */
    public synchronized void startOrResumePlayback(long durationSeconds) {
        if (playing) {
            // 已在播放：保持进度与时钟，不打断；但若已自然播到末尾（未 stopIfEnded），
            // 点播放应视为「重播」从头开始，而不是让状态卡在 ended 却仍 playing。
            if (durationMillis > 0 && getPositionMillis() >= durationMillis) {
                startPlayback(durationSeconds);
            }
            return;
        }
        // 歌曲已播完（进度到达末尾）：点播放视为「重播」，从 0 秒开始，而不是停在末尾
        // 用 getPositionMillis()（暂停态即 positionBaseMillis）判定，覆盖「播放中自然播完」的残留态
        boolean ended = durationMillis > 0 && getPositionMillis() >= durationMillis;
        if (positionBaseMillis > 0 && !ended) {
            resumePlayback(); // 已暂停且有进度（未结束）→ 从暂停点续播
        } else {
            startPlayback(durationSeconds); // 新加载 / 从头 / 已结束重播
        }
    }

    /**
     * 播放握手·阶段一：<strong>准备</strong>播放指定歌曲（预缓冲阶段）。
     *
     * <p>服务端把歌曲的一部分载入内存以加速后续流式播放，但<strong>不启动</strong>权威播放时钟——
     * 时钟只有在 {@link #beginAuthoritativeClock()} 被调用时才开始。这样客户端可以先确认
     * 「音乐已就绪」，再阻塞等待服务端告知「从何处开始」，从而避免客户端本地进度基线（0.00m）
     * 与服务端权威基线（0.00m）不一致。</p>
     *
     * <ul>
     *   <li>切换到<strong>不同</strong>的歌，或当前歌已播放到末尾 → 进度归零（从头开始）。</li>
     *   <li>同一首歌且未结束（例如暂停后续播）→ 保留已固化的进度，从暂停处续播。</li>
     * </ul>
     *
     * @param songId          目标歌曲 ID
     * @param durationSeconds 歌曲总时长（秒）；≤0 表示未知
     */
    public synchronized void prepareForPlayback(String songId, long durationSeconds) {
        boolean newSong = !songId.equals(preparedSongId);
        // 用 getPositionMillis()（含播放中的实时段）判定是否已播完，覆盖「播放中自然播完」的残留态
        boolean ended = durationMillis > 0 && getPositionMillis() >= durationMillis;
        if (newSong || ended) {
            resetProgress(); // 从头开始：进度清零、播放标志复位（prepared 也会被一并清空后重新置位）
        }
        // 同一首且未结束：保留 positionBaseMillis（续播），playing 标志维持现状
        if (durationSeconds > 0) {
            this.durationMillis = durationSeconds * 1000L;
        }
        this.prepared = true;
        this.preparedSongId = songId;
    }

    /**
     * 播放握手·阶段二：<strong>开始</strong>权威播放时钟。仅当已通过
     * {@link #prepareForPlayback} 准备好时才生效——在此之前服务端时钟不动。
     *
     * @return 播放应开始的起始偏移（毫秒）：对新歌 / 重播为 0，对续播为暂停处；
     *         若尚未准备则返回 {@code -1}
     */
    public synchronized long beginAuthoritativeClock() {
        if (!prepared) {
            return -1;
        }
        // 已在播放：不要重置 segmentStartEpoch（否则会把「正在播的时钟」重新起算，导致进度跳变）；
        // 直接返回当前进度，作为「起始偏移」。
        if (playing) {
            return getPositionMillis();
        }
        this.segmentStartEpoch = System.currentTimeMillis();
        this.playing = true;
        return this.positionBaseMillis; // 起始偏移（毫秒）：供客户端作为进度条起点
    }

    /**
     * 原子地「准备 + 启动权威时钟」（两段式握手的合并版）。
     *
     * <p>供 {@code playSongById}（{@code /select}）这类一步到位的入口使用：把
     * {@link #prepareForPlayback} 与 {@link #beginAuthoritativeClock} 合成一次同步操作，
     * 避免两者拆成两次独立调用后，在并发下被其它 {@code playSongById} 交错，
     * 导致「A 的 start 启动的是 B 准备的那首」的会话级竞态。</p>
     *
     * @param songId          目标歌曲 ID
     * @param durationSeconds 歌曲总时长（秒）；≤0 表示未知
     * @return 播放应开始的起始偏移（毫秒），语义与 {@link #beginAuthoritativeClock()} 一致
     */
    public synchronized long prepareAndStart(String songId, long durationSeconds) {
        prepareForPlayback(songId, durationSeconds);
        return beginAuthoritativeClock();
    }

    /** 是否已准备好（阶段一完成、阶段二未调用）。 */
    public synchronized boolean isPrepared() {
        return prepared;
    }

    /** 是否已针对指定歌曲准备好（用于判断「续播」还是「切歌从头」）。 */
    public synchronized boolean isPreparedFor(String songId) {
        return prepared && songId != null && songId.equals(preparedSongId);
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
        long targetMillis = Math.max(0, positionSeconds) * 1000L;
        // clamp 到歌曲总时长：客户端传超大值（如 /seek?position=999999）时，不应让
        // positionBaseMillis 内部固化一个远超时长的值——它会在 pausePlayback 固化、
        // startOrResumePlayback 判 ended 时都携带这个脏值。收敛到 durationMillis 后
        // getPositionMillis 与内部状态保持一致。durationMillis<=0（未知时长）时不 clamp。
        if (durationMillis > 0 && targetMillis > durationMillis) {
            targetMillis = durationMillis;
        }
        this.positionBaseMillis = targetMillis;
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
        this.prepared = false;
        this.preparedSongId = null;
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

    /** 当前播放片段开始的权威 epoch 毫秒（服务端时钟基准）；未播放时为 0。 */
    public synchronized long getSegmentStartEpoch() {
        return segmentStartEpoch;
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

    /** 是否曾有客户端参与过同步握手。 */
    public synchronized boolean hasClientSyncParticipation() {
        return clientSyncParticipating;
    }

    /** 客户端最后同步时间（epoch 毫秒）；0 表示从未同步。 */
    public synchronized long getLastClientSyncEpochMillis() {
        return lastClientSyncEpochMillis;
    }
}
