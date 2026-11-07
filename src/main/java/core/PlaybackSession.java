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
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 单个播放会话的状态：播放队列、当前下标与播放模式。
 *
 * <p><b>线程安全：</b>会话会被多个 HTTP 请求线程共享，因此这里所有公开方法都加了
 * {@code synchronized}，把「读下标 → 计算
 * → 写下标」这类复合操作变成原子操作，避免并发调用 {@code next()/previous()/loadPlaylist()}
 * 时出现下标错乱、重复播放或 {@code IndexOutOfBoundsException}。
 * {@code playlist} 是普通 {@code ArrayList}，不允许在未持锁的情况下从外部遍历。</p>
 *
 * <p><b>刻意不持有时钟。</b>服务器不推算「现在播到第几秒」——播放位置归客户端所有
 * （浏览器用 audio.currentTime）。历史上曾为已移除的 CLI 引入过服务端权威时钟
 * （segmentStartEpoch / positionBaseMillis / 两段式 prepare+start），CLI 重构后
 * 它成为无消费者的内部自循环，已删除。若将来需要「服务器知道位置」（如多端同步），
 * 应走「客户端上报 + 最后已知快照」，不要复活服务器时钟。</p>
 *
 * <p>{@link #durationMillis} 仅是「当前歌曲总时长」这一条元数据，供展示使用，
 * 不参与任何位置 / 状态推算。</p>
 */
public class PlaybackSession {
    private final List<String> playlist;
    private int currentIndex;
    private PlayMode playMode;
    private final Random rng;
    /** SHUFFLE 模式下已播放下标的回溯栈，使「上一首」真正可用。仅在 SHUFFLE 路径上写入与读取。 */
    private final ArrayDeque<Integer> shuffleHistory = new ArrayDeque<>();
    /** 当前歌曲总时长（毫秒）；仅作元数据展示用，0 表示未知。 */
    private long durationMillis = 0;

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
        this.durationMillis = 0;
    }

    public synchronized void addSong(String musicId) {
        this.playlist.add(musicId);
    }

    /**
     * 原子地「若不存在且未达上限则追加」，供队列追加（{@code queue.add}）去重 + 上限检查使用。
     *
     * <p>把「是否已在队列 → 是否已达上限 → 追加」三步合成一次同步操作，避免并发两个
     * {@code addToQueue} 同时通过去重 / 上限检查而重复追加或突破 {@code maxSize}。</p>
     *
     * @param musicId 歌曲 ID
     * @param maxSize 队列上限
     * @return {@link QueueManager.AddResult#ADDED} 本次新增；{@code ALREADY_PRESENT} 已在队列；
     *         {@code QUEUE_FULL} 队列已达上限
     */
    public synchronized QueueManager.AddResult addIfAbsent(String musicId, int maxSize) {
        if (playlist.contains(musicId)) {
            return QueueManager.AddResult.ALREADY_PRESENT;
        }
        if (playlist.size() >= maxSize) {
            return QueueManager.AddResult.QUEUE_FULL;
        }
        playlist.add(musicId);
        return QueueManager.AddResult.ADDED;
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
        this.durationMillis = 0;
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

    /**
     * 前进到下一首（不校验有效性，等价于把队列里所有歌都视为有效）。
     * 供测试使用；引擎需要「跳过幽灵项」时改用 {@link #advanceToNextResolved(Function)}。
     */
    public synchronized String getNextSong() {
        return advanceToNextResolved(id -> id);
    }

    /**
     * 后退到上一首（不校验有效性）。语义同 {@link #getNextSong()}。
     */
    public synchronized String getPreviousSong() {
        return advanceToPreviousResolved(id -> id);
    }

    /**
     * 前进到下一首<strong>有效</strong>歌曲：按当前播放模式推进游标，途中遇到的无效歌曲
     * （幽灵队列项——曲库中已查不到的 ID）会被<strong>就地移除</strong>，保证游标绝不停在
     * 失效项上（否则后续 /current 会返回 empty）。
     *
     * @param isValid 判定一首歌是否仍有效；返回 {@code false} 的项将被当作幽灵移除
     * @return 命中的有效歌曲 ID；队列为空或全为幽灵项时返回 {@code null}
     */
    public synchronized String advanceToNextValid(Predicate<String> isValid) {
        return advanceToNextResolved(id -> isValid.test(id) ? id : null);
    }

    /**
     * 后退到上一首<strong>有效</strong>歌曲，跳过并移除幽灵项。语义与
     * {@link #advanceToNextValid(Predicate)} 对称（后退方向相反）。
     */
    public synchronized String advanceToPreviousValid(Predicate<String> isValid) {
        return advanceToPreviousResolved(id -> isValid.test(id) ? id : null);
    }

    /**
     * 前进到下一首<strong>有效</strong>歌曲，并返回「解析结果」而非 ID。
     *
     * <p>与 {@link #advanceToNextValid(Predicate)} 的游标推进 / 幽灵清理语义完全一致，区别在于
     * 校验与解析「合二为一」：{@code resolve} 对每个候选 ID 只调用一次，返回 {@code null} 表示
     * 幽灵项（就地移除），返回非 {@code null} 即命中——命中项正是调用方需要的解析对象。这样引擎
     * 就无需「先校验、再重查一次曲库」，从根上消除两次查询之间因并发重扫导致的窄竞态（校验通过后、
     * 重查前该曲被移出曲库）。</p>
     *
     * @param resolve 解析函数：输入歌曲 ID，输出解析结果；返回 {@code null} 视为幽灵项
     * @param <T>     解析结果类型
     * @return 命中的解析结果；队列为空或全为幽灵项时返回 {@code null}
     */
    public synchronized <T> T advanceToNextResolved(Function<String, T> resolve) {
        return switch (playMode) {
            case SINGLE -> advanceSingle(resolve);
            case SHUFFLE -> advanceShuffleNext(resolve);
            case REVERSE -> skipLinear(-1, resolve);
            case FORWARD -> skipLinear(1, resolve);
        };
    }

    /**
     * 后退到上一首<strong>有效</strong>歌曲并返回解析结果。语义与
     * {@link #advanceToNextResolved(Function)} 对称（后退方向相反）。
     */
    public synchronized <T> T advanceToPreviousResolved(Function<String, T> resolve) {
        return switch (playMode) {
            case SINGLE -> advanceSingle(resolve);
            case SHUFFLE -> advanceShufflePrevious(resolve);
            case REVERSE -> skipLinear(1, resolve);
            case FORWARD -> skipLinear(-1, resolve);
        };
    }

    /**
     * 移除当前下标指向的歌曲（用于清理幽灵队列项）。移除后 {@code currentIndex} 保持在原位置，
     * 指向「补位上来的后一个元素」；若移除的是队尾则收敛到新队尾；若队列因此变空则归 0 并清空
     * 随机回溯栈。
     *
     * @return 被移除的歌曲 ID；队列为空或下标非法时返回 {@code null}
     */
    public synchronized String removeCurrent() {
        if (playlist.isEmpty() || currentIndex < 0 || currentIndex >= playlist.size()) {
            return null;
        }
        String removed = playlist.remove(currentIndex);
        if (currentIndex >= playlist.size()) {
            currentIndex = playlist.size() - 1; // 移除的是队尾：收敛到新队尾
        }
        if (playlist.isEmpty()) {
            currentIndex = 0;
            shuffleHistory.clear();
        }
        return removed;
    }

    /**
     * 沿线性方向 {@code step}（{@code +1} 前进 / {@code -1} 后退）跳过幽灵项，返回第一首有效歌曲。
     * 途中幽灵项被就地移除；游标只在命中有效歌曲时更新到目标下标。
     *
     * <p>移除后补位进来的是「后一个元素」：正向（{@code +1}）时补位进来的正是下一个待检对象，
     * 需把游标回退一格以免下一轮跳过它；反向（{@code -1}）时真正候选在原位置前一格（未受移除
     * 影响），游标无需调整。</p>
     */
    private <T> T skipLinear(int step, Function<String, T> resolve) {
        if (playlist.isEmpty()) {
            return null;
        }
        int idx = currentIndex;
        int n = playlist.size();
        int remaining = n; // 每轮要么命中、要么至少移除一个幽灵项，最多 n 轮必然终止
        while (remaining-- > 0) {
            idx = Math.floorMod(idx + step, n);
            String candidate = playlist.get(idx);
            T resolved = resolve.apply(candidate);
            if (resolved != null) {
                currentIndex = idx;
                return resolved;
            }
            // 幽灵项：就地移除
            playlist.remove(idx);
            n = playlist.size();
            if (n == 0) {
                currentIndex = 0;
                shuffleHistory.clear();
                return null;
            }
            if (step > 0) {
                // 正向：补位进来的正是下一个待检对象，回退一格使下一轮 (idx+step) 恰好落到它
                idx = Math.floorMod(idx - 1, n);
            }
            // 反向（step < 0）：候选在原 idx-1 处，下一轮 (idx+step) 自然落到它，无需调整
        }
        return null; // 不可达：全为幽灵时上面已在 n==0 处返回
    }

    /**
     * SINGLE 模式：语义是「重读当前」。若当前曲已失效（幽灵），就地移除并顺延检查补位元素，
     * 直到找到有效歌曲或队列清空。
     */
    private <T> T advanceSingle(Function<String, T> resolve) {
        while (!playlist.isEmpty()) {
            String cur = playlist.get(currentIndex);
            T resolved = resolve.apply(cur);
            if (resolved != null) {
                return resolved;
            }
            removeCurrent();
        }
        return null;
    }

    /**
     * SHUFFLE 模式的「下一首」：随机选一首不同于当前的歌；命中幽灵项时移除并清空随机回溯栈
     * （历史下标会因移除而错位，与其给出错歌，不如让「上一首」安全地重播当前），然后重选。
     */
    private <T> T advanceShuffleNext(Function<String, T> resolve) {
        if (playlist.isEmpty()) {
            return null;
        }
        if (playlist.size() == 1) {
            String only = playlist.get(0);
            T resolved = resolve.apply(only);
            if (resolved != null) {
                currentIndex = 0;
                return resolved;
            }
            playlist.clear();
            currentIndex = 0;
            shuffleHistory.clear();
            return null;
        }
        int guard = playlist.size() + 1;
        while (guard-- > 0) {
            if (playlist.isEmpty()) {
                currentIndex = 0;
                shuffleHistory.clear();
                return null;
            }
            int randomIndex;
            do {
                randomIndex = rng.nextInt(playlist.size());
            } while (randomIndex == currentIndex && playlist.size() > 1);
            String candidate = playlist.get(randomIndex);
            T resolved = resolve.apply(candidate);
            if (resolved != null) {
                shuffleHistory.addLast(currentIndex);
                if (shuffleHistory.size() > 1000) {
                    shuffleHistory.removeFirst();
                }
                currentIndex = randomIndex;
                return resolved;
            }
            // 幽灵项：移除并清空历史，随后重选
            playlist.remove(randomIndex);
            shuffleHistory.clear();
            if (playlist.isEmpty()) {
                currentIndex = 0;
                return null;
            }
            if (currentIndex > randomIndex) {
                currentIndex--; // 当前曲在被移除项之后，左移一格保持指向同一首
            }
        }
        return null;
    }

    /**
     * SHUFFLE 模式的「上一首」：回溯随机历史。历史耗尽时重播当前；历史指向的歌曲已失效（幽灵）
     * 时就地移除，并退化为重播当前。
     */
    private <T> T advanceShufflePrevious(Function<String, T> resolve) {
        if (playlist.isEmpty()) {
            return null;
        }
        Integer prev;
        while ((prev = shuffleHistory.pollLast()) != null
                && (prev < 0 || prev >= playlist.size())) {
            // 丢弃因队列缩短而失效的越界历史下标
        }
        if (prev == null) {
            return advanceSingle(resolve); // 无历史：重播当前（无效则顺带清理）
        }
        String candidate = playlist.get(prev);
        T resolved = resolve.apply(candidate);
        if (resolved != null) {
            currentIndex = prev;
            return resolved;
        }
        // 历史指向幽灵项：就地移除（历史已弹出，无法再回溯更深），随后退化为重播当前
        playlist.remove(prev.intValue());
        shuffleHistory.clear();
        if (playlist.isEmpty()) {
            currentIndex = 0;
            return null;
        }
        if (currentIndex > prev) {
            currentIndex--;
        } else if (currentIndex >= playlist.size()) {
            currentIndex = playlist.size() - 1;
        }
        return advanceSingle(resolve);
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

    /** 当前歌曲总时长（毫秒）；仅作元数据展示用，0 表示未知。 */
    public synchronized long getDurationMillis() {
        return durationMillis;
    }

    /** 仅写入当前歌曲总时长（秒 → 毫秒），作为元数据供展示；不影响任何播放状态。 */
    public synchronized void setDurationSeconds(long durationSeconds) {
        this.durationMillis = Math.max(0, durationSeconds) * 1000L;
    }
}
