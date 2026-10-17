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

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistTest {

    @Test
    void newPlaylistIsEmptyAndInitialised() {
        Playlist playlist = new Playlist("我的最爱", "测试用");

        assertNotNull(playlist.getId());
        assertEquals("我的最爱", playlist.getName());
        assertEquals("测试用", playlist.getDescription());
        assertEquals(0, playlist.getSize());
        assertNotNull(playlist.getCreatedAt());
        assertNotNull(playlist.getUpdatedAt());
    }

    @Test
    void addMusicReturnsTrueOnlyWhenNewlyAdded() {
        Playlist playlist = new Playlist("我的最爱", "");

        assertTrue(playlist.addMusic("song-1"));
        assertFalse(playlist.addMusic("song-1"), "重复添加应返回 false");
        assertEquals(1, playlist.getSize(), "重复添加不应增加数量");
    }

    @Test
    void removeMusicReturnsTrueOnlyWhenActuallyRemoved() {
        Playlist playlist = new Playlist("我的最爱", "");
        playlist.addMusic("song-1");

        assertTrue(playlist.removeMusic("song-1"));
        assertFalse(playlist.removeMusic("song-1"), "移除不存在的歌曲应返回 false");
        assertEquals(0, playlist.getSize());
    }

    @Test
    void updatedAtIsUntouchedByNoOpChanges() {
        Playlist playlist = new Playlist("我的最爱", "");
        playlist.addMusic("song-1");
        LocalDateTime afterAdd = playlist.getUpdatedAt();

        playlist.addMusic("song-1");            // 重复添加，无实际变更
        playlist.removeMusic("does-not-exist"); // 移除不存在的歌曲，无实际变更

        assertEquals(afterAdd, playlist.getUpdatedAt(), "无实际变更时不应刷新更新时间");
    }

    @Test
    void setMusicIdsNullFallsBackToEmptyList() {
        Playlist playlist = new Playlist("我的最爱", "");

        playlist.setMusicIds(null);

        assertNotNull(playlist.getMusicIds());
        assertTrue(playlist.addMusic("song-1"));
        assertEquals(1, playlist.getSize());
    }

    @Test
    void setMusicIdsUsesProvidedList() {
        Playlist playlist = new Playlist("我的最爱", "");

        playlist.setMusicIds(new ArrayList<>(List.of("a", "b", "c")));

        assertEquals(3, playlist.getSize());
        assertEquals(List.of("a", "b", "c"), playlist.getMusicIds());
    }

    @Test
    void getMusicIdsReturnsReadOnlySnapshot() {
        Playlist playlist = new Playlist("我的最爱", "");
        playlist.addMusic("song-1");
        List<String> snapshot = playlist.getMusicIds();

        // 快照本身不可变：调用方无法绕过 Playlist 的方法改动歌单状态
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("song-2"));

        // 歌单后续变化不应影响已取出的快照
        playlist.addMusic("song-3");
        assertEquals(List.of("song-1"), snapshot, "已取出的快照应保持不变");
        assertEquals(List.of("song-1", "song-3"), playlist.getMusicIds());
    }

    @Test
    void clearMusicEmptiesListAndRefreshesUpdatedAt() {
        Playlist playlist = new Playlist("我的最爱", "");
        playlist.addMusic("song-1");
        LocalDateTime afterAdd = playlist.getUpdatedAt();

        playlist.clearMusic();

        assertEquals(0, playlist.getSize());
        assertTrue(playlist.getMusicIds().isEmpty());
        assertFalse(playlist.getUpdatedAt().isBefore(afterAdd), "清空属于实际变更，应刷新更新时间");
    }

    @Test
    void reorderMusicAcceptsValidPermutation() {
        Playlist playlist = new Playlist("我的最爱", "");
        playlist.addMusic("a");
        playlist.addMusic("b");
        playlist.addMusic("c");

        assertTrue(playlist.reorderMusic(List.of("c", "a", "b")));

        assertEquals(List.of("c", "a", "b"), playlist.getMusicIds());
        assertEquals(3, playlist.getSize());
    }

    @Test
    void reorderMusicRejectsSubset() {
        Playlist playlist = new Playlist("我的最爱", "");
        playlist.addMusic("a");
        playlist.addMusic("b");
        playlist.addMusic("c");

        // 只传两个 ID：若被接受，歌曲 c 会被静默丢弃
        assertFalse(playlist.reorderMusic(List.of("c", "a")));

        assertEquals(List.of("a", "b", "c"), playlist.getMusicIds(), "子集不应改动歌单");
        assertEquals(3, playlist.getSize());
    }

    @Test
    void reorderMusicRejectsDuplicateIds() {
        Playlist playlist = new Playlist("我的最爱", "");
        playlist.addMusic("a");
        playlist.addMusic("b");

        // 传重复 ID 且长度一致：若被接受，会产生重复歌曲
        assertFalse(playlist.reorderMusic(Arrays.asList("a", "a")));

        assertEquals(List.of("a", "b"), playlist.getMusicIds(), "重复 ID 不应改动歌单");
    }

    @Test
    void reorderMusicRejectsUnknownId() {
        Playlist playlist = new Playlist("我的最爱", "");
        playlist.addMusic("a");
        playlist.addMusic("b");

        assertFalse(playlist.reorderMusic(List.of("a", "ghost")));

        assertEquals(List.of("a", "b"), playlist.getMusicIds(), "含未知 ID 不应改动歌单");
    }

    @Test
    void reorderMusicRejectsNullEmptyAndWrongLength() {
        Playlist playlist = new Playlist("我的最爱", "");
        playlist.addMusic("a");

        assertFalse(playlist.reorderMusic(null));
        assertFalse(playlist.reorderMusic(List.of()));
        assertFalse(playlist.reorderMusic(Arrays.asList("a", "a")));

        assertEquals(List.of("a"), playlist.getMusicIds(), "非法入参不应改动歌单");
    }

    /**
     * 并发读写压测。
     *
     * <p>此前 {@code musicIds} 是裸 {@code ArrayList} 且 {@code getMusicIds()} 直接返回内部集合，
     * 「一个请求读歌单详情（遍历） + 另一个请求加歌（写入）」会撞出
     * {@code ConcurrentModificationException}。本用例用一个读线程持续制作快照并遍历、
     * 四个写线程并发增删，任何并发缺陷都会以异常形式从 {@code future.get()} 抛出。</p>
     *
     * <p>同时验证「检查是否已存在 → 追加」的原子性：所有增删成对出现，最终应回到初始数量、
     * 且不产生重复项。</p>
     */
    @Test
    void concurrentMutationAndSnapshottingDoesNotThrow() throws Exception {
        final int initialSize = 50;
        Playlist playlist = new Playlist("我的最爱", "");
        for (int i = 0; i < initialSize; i++) {
            playlist.addMusic("seed-" + i);
        }

        final int writers = 4;
        final int iterations = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        // 读线程：不断取只读快照并遍历
        futures.add(pool.submit(() -> {
            await(start);
            for (int i = 0; i < iterations; i++) {
                for (String id : playlist.getMusicIds()) {
                    assertNotNull(id);
                }
                assertTrue(playlist.getSize() >= initialSize,
                        "写线程只是成对增删，数量不应掉到初始值以下");
            }
            return null;
        }));

        // 写线程：并发对同一个歌单增删
        for (int w = 0; w < writers; w++) {
            final int worker = w;
            futures.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < iterations; i++) {
                    String id = "song-" + worker + "-" + i;
                    playlist.addMusic(id);
                    playlist.removeMusic(id);
                }
                return null;
            }));
        }

        start.countDown();
        try {
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "线程池应正常结束");
        assertEquals(initialSize, playlist.getSize(), "增删成对，最终应回到初始数量");
        assertEquals(initialSize, playlist.getMusicIds().stream().distinct().count(),
                "不应出现重复歌曲（说明「检查 + 追加」是原子的）");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
