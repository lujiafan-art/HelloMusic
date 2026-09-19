package com;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class PlaybackSession {
    private final List<String> playlist;        // 当前播放列表（歌曲ID列表）
    private int currentIndex;                   // 当前播放位置
    private PlayMode playMode;                  // 播放模式
    private final Random random;

    public PlaybackSession() {
        this.playlist = new ArrayList<>();
        this.currentIndex = 0;
        this.playMode = PlayMode.FORWARD;
        this.random = new Random();
    }

    // 设置播放列表
    public void setPlaylist(List<String> musicIds) {
        this.playlist.clear();
        this.playlist.addAll(musicIds);
        this.currentIndex = 0;
    }

    // 添加歌曲到播放列表
    public void addSong(String musicId) {
        this.playlist.add(musicId);
    }

    // 清空播放列表
    public void clear() {
        this.playlist.clear();
        this.currentIndex = 0;
    }

    // 获取当前播放歌曲ID
    public String getCurrentSong() {
        if (playlist.isEmpty()) {
            return null;
        }
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            return null;
        }
        return playlist.get(currentIndex);
    }

    // 获取下一首歌曲ID
    public String getNextSong() {
        if (playlist.isEmpty()) {
            return null;
        }

        switch (playMode) {
            case SINGLE:
                // 单曲循环：返回当前歌曲
                return getCurrentSong();

            case SHUFFLE:
                // 随机播放：随机选择一首
                if (playlist.size() == 1) {
                    return playlist.get(0);
                }
                int randomIndex;
                do {
                    randomIndex = random.nextInt(playlist.size());
                } while (randomIndex == currentIndex && playlist.size() > 1);
                currentIndex = randomIndex;
                return playlist.get(currentIndex);

            case REVERSE:
                // 逆向播放
                if (currentIndex <= 0) {
                    currentIndex = playlist.size() - 1;
                } else {
                    currentIndex--;
                }
                return playlist.get(currentIndex);

            case FORWARD:
            default:
                // 正向播放
                if (currentIndex >= playlist.size() - 1) {
                    currentIndex = 0;
                } else {
                    currentIndex++;
                }
                return playlist.get(currentIndex);
        }
    }

    // 获取上一首歌曲ID
    public String getPreviousSong() {
        if (playlist.isEmpty()) {
            return null;
        }

        switch (playMode) {
            case SINGLE:
                return getCurrentSong();

            case SHUFFLE:
                return getCurrentSong();

            case REVERSE:
                // 逆向模式下，上一首 = 正向的下一首
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

    // 跳转到指定位置
    public boolean seekTo(int index) {
        if (index < 0 || index >= playlist.size()) {
            return false;
        }
        this.currentIndex = index;
        return true;
    }

    // 跳转到指定歌曲
    public boolean seekToSong(String musicId) {
        int index = playlist.indexOf(musicId);
        if (index < 0) {
            return false;
        }
        this.currentIndex = index;
        return true;
    }

    // 获取当前播放位置
    public int getCurrentIndex() {
        return currentIndex;
    }

    // 获取播放列表大小
    public int getSize() {
        return playlist.size();
    }

    // 判断是否为空
    public boolean isEmpty() {
        return playlist.isEmpty();
    }

    // 获取当前播放模式
    public PlayMode getPlayMode() {
        return playMode;
    }

    // 设置播放模式
    public void setPlayMode(PlayMode playMode) {
        this.playMode = playMode;
    }

    // 切换播放模式（循环切换）
    public PlayMode nextPlayMode() {
        PlayMode[] modes = PlayMode.values();
        int nextOrdinal = (playMode.ordinal() + 1) % modes.length;
        this.playMode = modes[nextOrdinal];
        return this.playMode;
    }

    // 获取当前播放列表（只读）
    public List<String> getPlaylist() {
        return new ArrayList<>(playlist);
    }

    // 是否到达列表末尾
    public boolean isEndOfList() {
        if (playlist.isEmpty()) {
            return true;
        }
        if (playMode == PlayMode.SINGLE || playMode == PlayMode.SHUFFLE) {
            return false;
        }
        return currentIndex >= playlist.size() - 1;
    }
}