package com;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Playlist {
    private String id;
    private String name;
    private String description;
    private List<String> musicIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Playlist() {
        this.id = java.util.UUID.randomUUID().toString();
        this.musicIds = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Playlist(String name, String description) {
        this();
        this.name = name;
        this.description = description;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getMusicIds() { return musicIds; }
    public void setMusicIds(List<String> musicIds) { this.musicIds = musicIds; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // 添加歌曲
    public void addMusic(String musicId) {
        if (!musicIds.contains(musicId)) {
            musicIds.add(musicId);
            this.updatedAt = LocalDateTime.now();
        }
    }

    // 移除歌曲
    public void removeMusic(String musicId) {
        musicIds.remove(musicId);
        this.updatedAt = LocalDateTime.now();
    }

    // 获取歌曲数量
    public int getSize() {
        return musicIds.size();
    }
}