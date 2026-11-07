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
    void advanceToNextValidSkipsAndRemovesGhost() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "g", "b"));
        session.setPlayMode(PlayMode.FORWARD);

        String next = session.advanceToNextValid(id -> !"g".equals(id));

        assertEquals("b", next, "应跳过幽灵项 g 并返回 b");
        assertEquals(List.of("a", "b"), session.getPlaylist(), "幽灵项 g 应被就地移除");
        assertEquals(1, session.getCurrentIndex());
        assertEquals("b", session.getCurrentSong());
    }

    @Test
    void advanceToPreviousValidSkipsAndRemovesGhost() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "g", "b"));
        session.setPlayMode(PlayMode.FORWARD);
        session.setCurrentIndex(2); // 当前 b

        String prev = session.advanceToPreviousValid(id -> !"g".equals(id));

        assertEquals("a", prev, "应跳过幽灵项 g 并返回 a");
        assertEquals(List.of("a", "b"), session.getPlaylist());
        assertEquals("a", session.getCurrentSong());
    }

    @Test
    void removeCurrentRemovesAndKeepsCursorInBounds() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b", "c"));
        session.setCurrentIndex(1); // b

        assertEquals("b", session.removeCurrent());
        assertEquals(List.of("a", "c"), session.getPlaylist());
        assertEquals(1, session.getCurrentIndex(), "补位后游标应指向原位置的 c");
        assertEquals("c", session.getCurrentSong());

        // 移除队尾：游标收敛到新队尾
        assertEquals("c", session.removeCurrent());
        assertEquals(List.of("a"), session.getPlaylist());
        assertEquals(0, session.getCurrentIndex(), "移除队尾后收敛到新队尾");

        // 移除最后一个：队列变空、下标归 0
        assertEquals("a", session.removeCurrent());
        assertTrue(session.isEmpty());
        assertEquals(0, session.getCurrentIndex());
        assertNull(session.removeCurrent(), "空队列移除应返回 null");
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
    void ensureSongPresentLocatesWithoutDuplicateAndAppends() {
        PlaybackSession session = new PlaybackSession();
        session.setPlaylist(List.of("a", "b"));

        assertEquals(1, session.ensureSongPresent("b"), "已存在应返回原下标，不重复追加");
        assertEquals(2, session.getSize(), "已存在的歌不应被重复追加");
        assertEquals(2, session.ensureSongPresent("c"), "不存在应追加并返回新下标");
        assertEquals(List.of("a", "b", "c"), session.getPlaylist());
    }
}
