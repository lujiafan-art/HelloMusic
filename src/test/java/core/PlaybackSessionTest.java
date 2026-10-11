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
}
