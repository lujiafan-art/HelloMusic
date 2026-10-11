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
    }

    public synchronized void addSong(String musicId) {
        this.playlist.add(musicId);
    }

    public synchronized void clear() {
        this.playlist.clear();
        this.currentIndex = 0;
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
            case SHUFFLE:
                return getCurrentSong();

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
}
