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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
