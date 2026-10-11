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
}