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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Playlist {
    private String id;
    private String name;
    private String description;

    /**
     * 歌曲 ID 列表。
     *
     * <p>刻意使用 {@link CopyOnWriteArrayList} 而非裸 {@code ArrayList}：同一个歌单会被多个
     * Tomcat 请求线程并发读写（一个请求在加歌、另一个在读歌单详情），而 {@code CopyOnWriteArrayList}
     * 的迭代天然是快照语义，不会像 {@code ArrayList} 那样在遍历中被并发修改时抛
     * {@code ConcurrentModificationException}。</p>
     */
    private CopyOnWriteArrayList<String> musicIds;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Playlist() {
        this.id = java.util.UUID.randomUUID().toString();
        this.musicIds = new CopyOnWriteArrayList<>();
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

    /**
     * 返回歌曲 ID 的**只读快照**。
     *
     * <p>刻意不返回内部集合：调用方拿到的副本既无法改动歌单状态，遍历时也不会被并发写入干扰。
     * 需要修改歌单请走 {@link #addMusic} / {@link #removeMusic} / {@link #clearMusic}。</p>
     */
    public List<String> getMusicIds() {
        return Collections.unmodifiableList(new ArrayList<>(musicIds));
    }

    public void setMusicIds(List<String> musicIds) {
        // 反序列化时若 JSON 中该字段为 null，回退为空列表，避免后续 NPE
        this.musicIds = new CopyOnWriteArrayList<>(musicIds != null ? musicIds : List.of());
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 添加歌曲。
     *
     * <p>{@code synchronized} 使「检查是否已存在 → 追加」成为原子操作；
     * 仅靠 {@code CopyOnWriteArrayList} 只能保证单次写入安全，无法防止并发重复添加。</p>
     *
     * @return 本次是否真的新增了歌曲；若已存在则返回 {@code false} 且不修改更新时间
     */
    public synchronized boolean addMusic(String musicId) {
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
    public synchronized boolean removeMusic(String musicId) {
        if (!musicIds.remove(musicId)) {
            return false;
        }
        this.updatedAt = LocalDateTime.now();
        return true;
    }

    /**
     * 清空歌单中的全部歌曲并刷新更新时间。
     *
     * <p>与 {@link #getMusicIds()} 配套：外部拿到的是只读快照，清空必须由本方法完成。</p>
     */
    public synchronized void clearMusic() {
        musicIds.clear();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 重排序歌单中的歌曲。
     *
     * <p>入参必须是当前歌单的<b>完整排列</b>：长度一致、无重复、且每个 ID 都确实在歌单中。
     * 任一条件不满足即返回 {@code false} 且不改动歌单状态——防止调用方传入子集导致歌曲被
     * 静默丢弃，或传入重复 ID 制造重复歌曲。</p>
     *
     * @param newOrder 目标顺序（歌曲 ID 列表）
     * @return 本次是否真的重排了歌单；入参不是完整排列时返回 {@code false}
     */
    public synchronized boolean reorderMusic(List<String> newOrder) {
        if (newOrder == null || newOrder.size() != musicIds.size()) {
            return false;
        }
        // 去重校验：containsAll 是集合语义，本身拦不住重复元素
        if (newOrder.stream().distinct().count() != newOrder.size()) {
            return false;
        }
        // 存在性校验：每个 ID 都必须真的在歌单里（同时排除掉 null 元素等脏数据）
        if (!musicIds.containsAll(newOrder)) {
            return false;
        }
        musicIds = new CopyOnWriteArrayList<>(newOrder);
        this.updatedAt = LocalDateTime.now();
        return true;
    }

    // 获取歌曲数量
    public int getSize() {
        return musicIds.size();
    }
}
