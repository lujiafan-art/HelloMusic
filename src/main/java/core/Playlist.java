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
    public void setMusicIds(List<String> musicIds) {
        // 反序列化时若 JSON 中该字段为 null，回退为空列表，避免后续 NPE
        this.musicIds = musicIds != null ? musicIds : new ArrayList<>();
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 添加歌曲。
     *
     * @return 本次是否真的新增了歌曲；若已存在则返回 {@code false} 且不修改更新时间
     */
    public boolean addMusic(String musicId) {
        if (musicIds.contains(musicId)) {
            return false;
        }
        musicIds.add(musicId);
        this.updatedAt = LocalDateTime.now();
        return true;
    }

    /**
     * 移除歌曲。
     *
     * @return 本次是否真的移除了歌曲；若原本不存在则返回 {@code false} 且不修改更新时间
     */
    public boolean removeMusic(String musicId) {
        if (!musicIds.remove(musicId)) {
            return false;
        }
        this.updatedAt = LocalDateTime.now();
        return true;
    }

    // 获取歌曲数量
    public int getSize() {
        return musicIds.size();
    }
}