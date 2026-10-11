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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlaybackService {

    private final MusicScannerService scannerService;
    private final PlaylistService playlistService;
    private final ConfigManager configManager;
    // 支持多个播放会话（可扩展为多用户）
    private final Map<String, PlaybackSession> sessions;

    @Autowired
    public PlaybackService(MusicScannerService scannerService,
                           PlaylistService playlistService,
                           ConfigManager configManager) {
        this.scannerService = scannerService;
        this.playlistService = playlistService;
        this.configManager = configManager;
        this.sessions = new ConcurrentHashMap<>();

        // 默认会话：恢复上次保存的播放模式，实现配置持久化
        PlaybackSession defaultSession = new PlaybackSession();
        defaultSession.setPlayMode(parseMode(configManager.getLastPlayMode()));
        this.sessions.put("default", defaultSession);
    }

    // 获取当前播放模式
    public PlayMode getCurrentMode() {
        return getSession().getPlayMode();
    }

    // 切换播放模式
    public PlayMode switchMode() {
        PlaybackSession session = getSession();
        PlayMode newMode = session.nextPlayMode();
        persistMode(newMode);
        System.out.println("🔄 切换播放模式: " + newMode.getDisplayName());
        return newMode;
    }

    // 设置播放模式
    public PlayMode setMode(PlayMode mode) {
        PlaybackSession session = getSession();
        session.setPlayMode(mode);
        persistMode(mode);
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

    /**
     * 根据歌单 ID 把歌单加载到播放队列。
     * 会自动过滤掉已不在音乐库中的失效歌曲。
     *
     * @return 歌单不存在或过滤后为空时返回 {@code false}
     */
    public boolean loadPlaylistById(String playlistId) {
        Playlist playlist = playlistService.getPlaylist(playlistId);
        if (playlist == null) {
            return false;
        }
        List<String> validIds = playlist.getMusicIds().stream()
                .filter(id -> scannerService.getMusicById(id).isPresent())
                .toList();
        if (validIds.isEmpty()) {
            return false;
        }
        loadPlaylist(validIds);
        return true;
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

        // 注意：这里必须用 HashMap，因为队列为空时 currentSong 为 null，
        // 而 Map.of 不允许 null 值，会抛 NullPointerException
        Map<String, Object> info = new HashMap<>();
        info.put("playMode", session.getPlayMode().getDisplayName());
        info.put("currentIndex", session.getCurrentIndex());
        info.put("total", session.getSize());
        info.put("currentSong", current);
        info.put("isEmpty", session.isEmpty());
        return info;
    }

    // 获取播放队列（仅ID列表）
    public List<String> getQueue() {
        return getSession().getPlaylist();
    }

    private PlaybackSession getSession() {
        // computeIfAbsent 保证取到的会话一定被存入 map，不会返回游离对象
        return sessions.computeIfAbsent("default", key -> new PlaybackSession());
    }

    /** 把当前播放模式写回配置文件，使其在重启后保留。 */
    private void persistMode(PlayMode mode) {
        configManager.savePlayMode(mode.name());
    }

    /** 安全解析配置中的播放模式名，非法值回退到 FORWARD。 */
    private PlayMode parseMode(String name) {
        if (name == null) {
            return PlayMode.FORWARD;
        }
        try {
            return PlayMode.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PlayMode.FORWARD;
        }
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
}
