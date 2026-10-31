/*
 * HelloMusic - Local music server with Unix socket control plane and HTTP media stream
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

public class MusicFile {
    private String id;
    private String fileName;
    private String filePath;
    private long fileSize;
    private String extension;
    private LocalDateTime lastModified;
    private MusicMetadata metadata;
    private String url;

    public MusicFile() {}

    public MusicFile(String id, String fileName, String filePath, long fileSize,
                     String extension, LocalDateTime lastModified) {
        this.id = id;
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.extension = extension;
        this.lastModified = lastModified;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = extension; }
    public LocalDateTime getLastModified() { return lastModified; }
    public void setLastModified(LocalDateTime lastModified) { this.lastModified = lastModified; }
    public MusicMetadata getMetadata() { return metadata; }
    public void setMetadata(MusicMetadata metadata) { this.metadata = metadata; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    // ===== 显示名（元数据优先，占位符视为「没有」） =====

    /** 标题：取自元数据；缺失或为占位符「Unknown Title」时回退文件名。 */
    public String displayTitle() {
        String title = metadataTitle();
        return title != null ? title : fileName;
    }

    /** 艺术家：取自元数据；缺失或为占位符「Unknown Artist」时返回 {@code null}（表示「无」）。 */
    public String displayArtist() {
        String artist = metadataArtist();
        return artist;
    }

    /**
     * 「标题 - 艺术家」显示名：艺术家缺失时只返回标题，两者都缺失时返回文件名。
     * 列表、当前播放、Web 快照共用这一份实现，避免各处重复拼装。
     */
    public String titleArtist() {
        String title = displayTitle();
        String artist = displayArtist();
        return artist == null ? title : title + " - " + artist;
    }

    private String metadataTitle() {
        if (metadata == null) return null;
        String t = metadata.getTitle();
        return (t == null || t.isEmpty() || "Unknown Title".equals(t)) ? null : t;
    }

    private String metadataArtist() {
        if (metadata == null) return null;
        String a = metadata.getArtist();
        return (a == null || a.isEmpty() || "Unknown Artist".equals(a)) ? null : a;
    }
}