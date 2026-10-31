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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackSessionTest {

    @Test
    void emptySessionReturnsNull() {
        PlaybackSession session = new PlaybackSession();

        assertTrue(session.isEmpty());
        assertEquals(0, session.getSize());
        assertNull(session.getCurrentSong());
        assertNull(session.getNextSong());
        assertNull(session.getPreviousSong());
    }

    @Test
    void setPlaylistResetsPosition() {
        PlaybackSession session = new PlaybackSession();
        session.getNextSong();
        session.setPlaylist(List.of("a", "b", "c"));

        assertEquals(3, session.getSize());
        assertEquals(0, session.getCurrentIndex());
        assertEquals("a", session.getCurrentSong());
        assertFalse(session.isEmpty());
    }

    @Test
    void forwardModeWrapsAround() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b", "c"));
        session.setPlayMode(PlayMode.FORWARD);

        assertEquals("b", session.getNextSong());
        assertEquals("c", session.getNextSong());
        assertEquals("a", session.getNextSong(), "正向播放到末尾应回到开头");
    }

    @Test
    void forwardPreviousWrapsToEnd() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b", "c"));
        session.setPlayMode(PlayMode.FORWARD);

        assertEquals("c", session.getPreviousSong(), "正向播放的上一首在开头处应回到末尾");
    }

    @Test
    void reverseModeWalksBackwards() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b", "c"));
        session.setPlayMode(PlayMode.REVERSE);

        assertEquals("c", session.getNextSong());
        assertEquals("b", session.getNextSong());
        assertEquals("a", session.getNextSong());
        assertEquals("c", session.getNextSong(), "逆向播放到开头应回到末尾");
    }

    @Test
    void singleModeRepeatsCurrentSong() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b"));
        session.setPlayMode(PlayMode.SINGLE);

        assertEquals("a", session.getNextSong());
        assertEquals("a", session.getNextSong());
        assertEquals("a", session.getPreviousSong());
        assertEquals(0, session.getCurrentIndex(), "单曲循环不应改变下标");
    }

    @Test
    void shuffleStaysInBoundsAndAvoidsImmediateRepeat() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b", "c", "d"));
        session.setPlayMode(PlayMode.SHUFFLE);

        String previous = session.getCurrentSong();
        for (int i = 0; i < 50; i++) {
            String current = session.getNextSong();
            assertNotNull(current);
            assertNotEquals(previous, current, "连续两次不应播出同一首");
            assertTrue(session.getCurrentIndex() >= 0 && session.getCurrentIndex() < 4);
            previous = current;
        }
    }

    @Test
    void shuffleWithSingleTrackKeepsReturningIt() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("only"));
        session.setPlayMode(PlayMode.SHUFFLE);

        assertEquals("only", session.getNextSong());
        assertEquals("only", session.getNextSong());
    }

    @Test
    void nextPlayModeCyclesThroughAllModes() {
        PlaybackSession session = new PlaybackSession();
        assertEquals(PlayMode.FORWARD, session.getPlayMode());

        assertEquals(PlayMode.REVERSE, session.nextPlayMode());
        assertEquals(PlayMode.SINGLE, session.nextPlayMode());
        assertEquals(PlayMode.SHUFFLE, session.nextPlayMode());
        assertEquals(PlayMode.FORWARD, session.nextPlayMode(), "应循环回到第一种模式");
    }

    @Test
    void addSongAppendsWithoutMovingPosition() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a"));
        session.getNextSong();

        session.addSong("b");

        assertEquals(2, session.getSize());
        assertEquals("b", session.getPlaylist().get(1));
    }

    @Test
    void clearResetsPlaylistAndPosition() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b"));
        session.getNextSong();

        session.clear();

        assertTrue(session.isEmpty());
        assertEquals(0, session.getCurrentIndex());
        assertNull(session.getCurrentSong());
    }

    @Test
    void getPlaylistReturnsDefensiveCopy() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a"));

        session.getPlaylist().add("hack");

        assertEquals(1, session.getSize(), "外部修改返回的列表不应影响会话内部状态");
    }

    @Test
    void concurrentNextNeverThrowsAndKeepsIndexValid() throws Exception {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b", "c", "d", "e", "f", "g", "h"));

        int threads = 8;
        int iterations = 2000;
        Thread[] workers = new Thread[threads];
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        assertNotNull(session.getNextSong());
                        assertNotNull(session.getPreviousSong());
                        session.getCurrentSong();
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
        }

        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertNull(failure.get(), () -> "并发访问不应抛异常，实际: " + failure.get());
        assertTrue(session.getCurrentIndex() >= 0 && session.getCurrentIndex() < 8);
    }

    @Test
    void shufflePreviousReturnsLastPlayedSong() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b", "c", "d", "e"));
        session.setPlayMode(PlayMode.SHUFFLE);

        // 加载后当前为 "a"（index 0），连播几首并记录播放顺序
        String s0 = session.getCurrentSong();
        String s1 = session.getNextSong();
        String s2 = session.getNextSong();
        String s3 = session.getNextSong();

        // previous 应严格按时间回溯到上一首，而不是重播当前
        assertEquals(s2, session.getPreviousSong());
        assertEquals(s1, session.getPreviousSong());
        assertEquals(s0, session.getPreviousSong());
        // 历史耗尽后再 previous：重播当前（不抛异常、下标合法）
        assertNotNull(session.getPreviousSong());
        assertTrue(session.getCurrentIndex() >= 0 && session.getCurrentIndex() < 5);
    }

    @Test
    void switchingModeClearsStaleShuffleHistory() {
        // 修复「SHUFFLE 切走再切回，上一首跳到旧会话的随机下标」：模式变化必须清空随机回溯栈
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b", "c", "d"));
        session.setPlayMode(PlayMode.SHUFFLE);
        session.getNextSong(); // 记录随机历史
        session.getNextSong();

        // 切到 FORWARD 再切回 SHUFFLE：旧历史应已清空
        session.setPlayMode(PlayMode.FORWARD);
        session.setPlayMode(PlayMode.SHUFFLE);

        String current = session.getCurrentSong();
        assertEquals(current, session.getPreviousSong(),
                "切模式后旧随机历史应被清空，previous 应重播当前而非跳到旧下标");
    }

    @Test
    void progressClockTracksPositionAndDuration() throws Exception {
        PlaybackSession session = new PlaybackSession();

        // 开始播放一首 100 秒的歌
        session.startOrResumePlayback(100);
        assertTrue(session.isPlaying());
        assertEquals(100_000L, session.getDurationMillis());
        assertTrue(session.getPositionMillis() >= 0 && session.getPositionMillis() < 1000,
                "刚开始播放时进度应接近 0");

        // 跳到第 40 秒：位置应约等于 40000ms（允许极小的时间流逝）
        session.seekPlayback(40);
        long afterSeek = session.getPositionMillis();
        assertTrue(afterSeek >= 40_000 && afterSeek < 41_000,
                "seek(40) 后进度应约 40000ms，实际: " + afterSeek);

        // 暂停后进度应被固化（连续读取不变）
        session.pausePlayback();
        assertFalse(session.isPlaying());
        long frozen1 = session.getPositionMillis();
        long frozen2 = session.getPositionMillis();
        assertEquals(frozen1, frozen2, "暂停后进度应冻结");

        // 超过时长的 seek 应被收敛到歌曲总时长
        session.startOrResumePlayback(100);
        session.seekPlayback(9999);
        assertEquals(100_000L, session.getPositionMillis(), "进度不应超过歌曲总时长");

        // 切歌保留 playing 标志，但进度归零、时长清零
        session.onSongChanged();
        assertTrue(session.isPlaying(), "onSongChanged 应保留 playing 标志");
        // playing 时 onSongChanged 会把 segmentStartEpoch 置为 now，getPositionMillis 返回
        // 「now 到调用时刻」的流逝，可能跨毫秒边界得到 1，故用 <1000 容忍亚毫秒漂移
        assertTrue(session.getPositionMillis() < 1000,
                "onSongChanged 后进度应归零，实际: " + session.getPositionMillis());
        assertEquals(0, session.getDurationMillis());

        // 彻底重置
        session.resetProgress();
        assertFalse(session.isPlaying());
        assertEquals(0, session.getPositionMillis());
        assertEquals(0, session.getDurationMillis());
    }

    @Test
    void resumePlaybackContinuesFromPausedPosition() {
        PlaybackSession session = new PlaybackSession();
        session.startOrResumePlayback(100);

        // 跳到第 30 秒，模拟已播放一段时间
        session.seekPlayback(30);
        session.pausePlayback();
        assertFalse(session.isPlaying());
        long frozen = session.getPositionMillis();
        assertTrue(frozen >= 30_000 && frozen < 31_000, "暂停点应约为 30000ms，实际: " + frozen);

        // 恢复播放：必须「从断点续播」，positionBaseMillis 不得被清零（否则会从头开始）
        session.startOrResumePlayback(100);
        assertTrue(session.isPlaying());
        long resumed = session.getPositionMillis();
        assertTrue(resumed >= 30_000, "恢复播放后进度应 >= 暂停点，实际: " + resumed);
        assertTrue(resumed < 31_500, "恢复播放不应从头开始，实际: " + resumed);
    }

    @Test
    void stopIfEndedPinsPositionAndStopsPlaying() {
        PlaybackSession session = new PlaybackSession();
        session.startOrResumePlayback(100);
        session.seekPlayback(100); // 跳到末尾
        session.stopIfEnded();

        assertFalse(session.isPlaying(), "播完后应停止 playing");
        assertEquals(100_000L, session.getPositionMillis(), "进度应被固化在歌曲末尾");

        // 重复调用不应改变已停住的状态
        session.stopIfEnded();
        assertFalse(session.isPlaying());
        assertEquals(100_000L, session.getPositionMillis());

        // 中途（未到末尾）不应被误停
        PlaybackSession mid = new PlaybackSession();
        mid.startOrResumePlayback(100);
        mid.seekPlayback(10);
        mid.stopIfEnded();
        assertTrue(mid.isPlaying(), "中途不应被 stopIfEnded 停住");
        assertEquals(10_000L, mid.getPositionMillis(), 2_000L);
    }

    @Test
    void setCurrentIndexRestoresWithinBounds() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b", "c"));
        session.setCurrentIndex(2);
        assertEquals(2, session.getCurrentIndex());
        assertEquals("c", session.getCurrentSong());

        // 越界下标被忽略，保持原值（避免持久化数据损坏时 IndexOutOfBounds）
        session.setCurrentIndex(99);
        assertEquals(2, session.getCurrentIndex());
        session.setCurrentIndex(-1);
        assertEquals(2, session.getCurrentIndex());

        // 空队列时不生效
        PlaybackSession empty = new PlaybackSession();
        empty.setCurrentIndex(3);
        assertEquals(0, empty.getCurrentIndex());
    }

    @Test
    void startOrResumePlaybackIsIdempotentWhilePlaying() {
        // 修复「/play 不幂等」：播放中再次触发不应把进度清零、从头重放
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b"));
        session.setCurrentIndex(0);
        session.startOrResumePlayback(100);
        session.seekPlayback(30); // 已播放到 30 秒
        assertTrue(session.isPlaying());
        long before = session.getPositionMillis();
        assertTrue(before >= 30_000 && before < 31_000, "跳转后约为 30000ms，实际: " + before);

        // 模拟播放中再次收到 /play：startOrResumePlayback 必须保持当前时钟
        session.startOrResumePlayback(100);
        assertTrue(session.isPlaying());
        long after = session.getPositionMillis();
        // 进度应保持在 30 秒附近（继续走表），绝不应被重置到接近 0
        assertTrue(after >= 30_000, "仍在播放时 /play 不应把进度清零，实际: " + after);
        assertTrue(after < 40_000, "仍在播放时 /play 不应从头开始，实际: " + after);
    }

    @Test
    void onSongChangedPreservesPlayingFlagForAutoAdvance() {
        // 修复「自动下一首失效」的服务端前提：切歌后若原本在播放，应继续播放（playing 保留），
        // 使 Web 端 /next 后能用 /play 续播，而不是被误判为已停止而卡死。
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b", "c"));
        session.setCurrentIndex(0);
        session.startOrResumePlayback(100);
        assertTrue(session.isPlaying());

        // 切歌（next()/previous() 内部会调用 onSongChanged）
        session.onSongChanged();
        assertTrue(session.isPlaying(), "切歌后应保留 playing 标志，便于连续播放");
        assertEquals(0L, session.getPositionMillis(), "切歌后进度应归零（从新歌开头算起）");
    }

    @Test
    void selectWithinQueueDoesNotReplaceOtherSongs() {
        // 修复「点曲库歌曲清空整个队列」：选播是「定位」而非「替换队列」
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b", "c"));

        // 模拟在现有队列中定位到 "b"：用 indexOf 找位置，再 setCurrentIndex，不能重设整个 playlist
        int idx = session.getPlaylist().indexOf("b");
        assertEquals(1, idx);
        session.setCurrentIndex(idx);
        assertEquals("b", session.getCurrentSong());

        // 队列里的其它歌曲必须原封不动
        assertEquals(3, session.getSize());
        assertTrue(session.getPlaylist().contains("a"));
        assertTrue(session.getPlaylist().contains("c"));
    }

    @Test
    void startOrResumePlaybackReplaysFromZeroWhenSongEnded() {
        // 修复「播完的歌再点播放会卡在 ended」：已结束应重播（从 0 开始），而非停在末尾
        PlaybackSession session = new PlaybackSession();
        session.startOrResumePlayback(100);   // 时长 100s
        session.seekPlayback(100);    // 跳到末尾（仍在播放）
        session.pausePlayback();      // 固化：playing=false，positionBaseMillis≈duration

        // 此时等价于「已播完」，再次 startOrResumePlayback 应视为重播
        session.startOrResumePlayback(100);
        assertTrue(session.isPlaying(), "重播应处于播放态");
        long pos = session.getPositionMillis();
        assertTrue(pos < 2_000L, "已结束的歌点播放应从 0 附近重播，而非停在末尾，实际: " + pos);
    }

    @Test
    void startOrResumePlaybackStillResumesFromPauseMidSong() {
        // 回归：确保「已结束重播」分支没有破坏「中途暂停后续播」这一既有行为
        PlaybackSession session = new PlaybackSession();
        session.startOrResumePlayback(100);
        session.seekPlayback(30);     // 暂停点 30s
        session.pausePlayback();

        session.startOrResumePlayback(100);
        assertTrue(session.isPlaying());
        long pos = session.getPositionMillis();
        assertTrue(pos >= 30_000 && pos < 31_500, "应从中途暂停点续播，实际: " + pos);
    }

    // ===== 两段式握手：prepareForPlayback + beginAuthoritativeClock =====

    @Test
    void beginAuthoritativeClockWithoutPrepareReturnsNegative() {
        // 未准备就 start：服务端时钟不应被非法启动
        PlaybackSession session = new PlaybackSession();
        assertEquals(-1, session.beginAuthoritativeClock(), "未 prepare 调用 start 应返回 -1");
        assertFalse(session.isPrepared(), "不应处于 prepared 状态");
    }

    @Test
    void prepareDoesNotStartClockUntilBegin() {
        // 阶段一（prepare）只准备、不启动权威时钟；阶段二（begin）才启动
        PlaybackSession session = new PlaybackSession();
        session.prepareForPlayback("A", 100);
        assertTrue(session.isPrepared(), "prepare 后应处于 prepared 状态");
        assertFalse(session.isPlaying(), "prepare 后时钟尚未启动（不应在播放）");
        assertEquals(0, session.getSegmentStartEpoch(), "prepare 后权威起点应为 0");

        long offset = session.beginAuthoritativeClock();
        assertEquals(0L, offset, "新歌起始偏移应为 0");
        assertTrue(session.isPlaying(), "begin 后应立即进入播放态");
        assertTrue(session.getSegmentStartEpoch() > 0, "begin 应写入权威起点时间戳");
    }

    @Test
    void prepareKeepsPositionForResumeThenStartReturnsOffset() {
        // 同一首歌、未结束 → prepare 保留进度；begin 返回续播偏移（暂停处）
        PlaybackSession session = new PlaybackSession();
        session.prepareForPlayback("A", 100);
        session.beginAuthoritativeClock();   // 从 0 开始播放
        session.seekPlayback(30);            // 播到 30s
        session.pausePlayback();             // 固化：positionBaseMillis=30000

        session.prepareForPlayback("A", 100); // 同一首、未结束 → 续播
        long offset = session.beginAuthoritativeClock();
        assertTrue(offset >= 30_000 && offset < 31_500, "续播起始偏移应约等于暂停处，实际: " + offset);
    }

    @Test
    void prepareResetsPositionForEndedSong() {
        // 已播完的歌再次 prepare → 从头重播（偏移归 0），而不是停在末尾
        PlaybackSession session = new PlaybackSession();
        session.prepareForPlayback("A", 100);
        session.beginAuthoritativeClock();
        session.seekPlayback(100);   // 跳到末尾
        session.pausePlayback();      // 固化：positionBaseMillis≈duration（已结束）

        session.prepareForPlayback("A", 100); // 已结束 → 从头
        assertEquals(0L, session.beginAuthoritativeClock(), "已结束的歌应重播，偏移归 0");
    }

    @Test
    void prepareForNewSongResetsPosition() {
        // 切到不同的歌 → prepare 应从头（偏移归 0），而非沿用上一首的进度
        PlaybackSession session = new PlaybackSession();
        session.prepareForPlayback("A", 100);
        session.beginAuthoritativeClock();
        session.seekPlayback(40);

        session.prepareForPlayback("B", 100); // 不同歌曲 → 从头
        assertEquals(0L, session.beginAuthoritativeClock(), "切歌应从头，偏移归 0");
        assertFalse(session.isPreparedFor("A"), "准备 B 后不应仍 prepared 于 A");
        assertTrue(session.isPreparedFor("B"));
    }

    @Test
    void ensureSongPresentLocatesWithoutDuplicateAndAppends() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b"));

        assertEquals(1, session.ensureSongPresent("b"), "已存在应返回原下标，不重复追加");
        assertEquals(2, session.getSize(), "已存在的歌不应被重复追加");
        assertEquals(2, session.ensureSongPresent("c"), "不存在应追加并返回新下标");
        assertEquals(List.of("a", "b", "c"), session.getPlaylist());
    }

    @Test
    void beginAuthoritativeClockDoesNotResetClockWhilePlaying() {
        // 修复「/prepare 对正在播放的歌调用会造成进度回退」：begin 在播放中应返回当前进度，
        // 而不是把 segmentStartEpoch 重置为 now（那会丢掉从旧 segmentStart 到现在的时长）。
        PlaybackSession session = new PlaybackSession();
        session.prepareForPlayback("A", 100);
        session.beginAuthoritativeClock(); // 从 0 开始播放
        session.seekPlayback(30);          // 播到 30s

        // 同一首、未结束：prepare 保留播放态；begin 不应重置时钟
        session.prepareForPlayback("A", 100);
        long offset = session.beginAuthoritativeClock();
        assertTrue(offset >= 30_000 && offset < 31_500,
                "播放中 begin 应返回当前进度而非重置时钟，实际: " + offset);
        assertTrue(session.isPlaying());
    }
}
