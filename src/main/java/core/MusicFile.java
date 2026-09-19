package com;

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