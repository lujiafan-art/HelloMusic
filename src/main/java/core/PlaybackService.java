package com;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlaybackService {

    private final MusicScannerService scannerService;
    // 支持多个播放会话（可扩展为多用户）
    private final Map<String, PlaybackSession> sessions;

    @Autowired
    public PlaybackService(MusicScannerService scannerService) {
        this.scannerService = scannerService;
        this.sessions = new ConcurrentHashMap<>();
        // 默认会话
        this.sessions.put("default", new PlaybackSession());
    }

    // 获取当前播放模式
    public PlayMode getCurrentMode() {
        return getSession().getPlayMode();
    }

    // 切换播放模式
    public PlayMode switchMode() {
        PlaybackSession session = getSession();
        PlayMode newMode = session.nextPlayMode();
        System.out.println("🔄 切换播放模式: " + newMode.getDisplayName());
        return newMode;
    }

    // 设置播放模式
    public PlayMode setMode(PlayMode mode) {
        PlaybackSession session = getSession();
        session.setPlayMode(mode);
        System.out.println("🔄 设置播放模式: " + mode.getDisplayName());
        return mode;
    }

    // 获取当前播放歌曲
    public MusicFile getCurrentSong() {
        PlaybackSession session = getSession();
        String songId = session.getCurrentSong();
        if (songId == null) {
            return null;
        }
        return scannerService.getMusicById(songId).orElse(null);
    }

    // 下一首
    public MusicFile next() {
        PlaybackSession session = getSession();
        String nextId = session.getNextSong();
        if (nextId == null) {
            return null;
        }
        MusicFile song = scannerService.getMusicById(nextId).orElse(null);
        if (song != null) {
            System.out.println("▶ 播放下一首: " + getDisplayName(song));
        }
        return song;
    }

    // 上一首
    public MusicFile previous() {
        PlaybackSession session = getSession();
        String prevId = session.getPreviousSong();
        if (prevId == null) {
            return null;
        }
        MusicFile song = scannerService.getMusicById(prevId).orElse(null);
        if (song != null) {
            System.out.println("⏮ 播放上一首: " + getDisplayName(song));
        }
        return song;
    }

    // 加载播放列表
    public void loadPlaylist(List<String> musicIds) {
        PlaybackSession session = getSession();
        session.setPlaylist(musicIds);
        System.out.println("📋 加载播放列表: " + musicIds.size() + " 首歌曲");
    }

    // 加载全部音乐
    public void loadAllMusic() {
        List<MusicFile> all = scannerService.getAllMusic();
        List<String> ids = all.stream().map(MusicFile::getId).toList();
        loadPlaylist(ids);
    }

    // 加载播放列表（根据歌单ID）
    public boolean loadPlaylistById(String playlistId) {
        // 这里可以调用 PlaylistService 获取歌单
        // 暂时返回 false，后续集成
        return false;
    }

    // 添加歌曲到队列
    public void addToQueue(String musicId) {
        getSession().addSong(musicId);
    }

    // 清空队列
    public void clearQueue() {
        getSession().clear();
    }

    // 获取播放队列信息
    public Map<String, Object> getQueueInfo() {
        PlaybackSession session = getSession();
        String currentId = session.getCurrentSong();
        MusicFile current = currentId != null ? scannerService.getMusicById(currentId).orElse(null) : null;

        return Map.of(
            "playMode", session.getPlayMode().getDisplayName(),
            "currentIndex", session.getCurrentIndex(),
            "total", session.getSize(),
            "currentSong", current,
            "isEmpty", session.isEmpty()
        );
    }

    // 获取播放队列（仅ID列表）
    public List<String> getQueue() {
        return getSession().getPlaylist();
    }

    private PlaybackSession getSession() {
        return sessions.getOrDefault("default", new PlaybackSession());
    }

    private String getDisplayName(MusicFile song) {
        if (song == null) {
            return "未知";
        }
        if (song.getMetadata() != null && song.getMetadata().getTitle() != null) {
            return song.getMetadata().getTitle();
        }
        return song.getFileName();
    }

    // 获取所有歌曲ID（辅助方法）
    public List<String> getAllMusicIds() {
        return scannerService.getAllMusic().stream()
                .map(MusicFile::getId)
                .toList();
    }
}