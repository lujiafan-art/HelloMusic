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

import jakarta.annotation.PostConstruct;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class MusicScannerService {
    // volatile 引用：扫描完成后一次性原子换引用，避免 clear()+putAll() 之间的空库窗口
    private volatile Map<String, MusicFile> musicLibrary = new ConcurrentHashMap<>();
    private final ConfigManager configManager;
    // 原子标志：多个触发源（启动 / @Scheduled / REST /scan / CLI scan）可能并发调用
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile long lastScanTime = 0;

    @Autowired
    public MusicScannerService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 应用启动时同步执行一次初始扫描。
     * 放在 @PostConstruct 而不是构造器里：此时依赖已注入、Bean 已构建完毕，
     * 既避免在构造期间做重活，也保证启动后音乐库立刻可用。
     * 后续刷新交给下面的 @Scheduled 定时任务。
     */
    @PostConstruct
    public void initScan() {
        scanLibrary();
    }

    @Scheduled(initialDelay = 300000, fixedDelay = 300000)
    public void scanLibrary() {
        // CAS 抢占：只有一个线程能把 false 翻转为 true，其余直接返回
        if (!scanning.compareAndSet(false, true)) {
            System.out.println("⏭ 已有扫描任务在执行，跳过本次触发");
            return;
        }

        try {
            System.out.println("🔍 Starting music library scan...");
            long startTime = System.currentTimeMillis();

            List<String> libraryPaths = configManager.getConfig().getMusicLibraryPaths();
            List<String> supportedExtensions = configManager.getConfig().getSupportedExtensions();

            Map<String, MusicFile> newLibrary = new ConcurrentHashMap<>();

            for (String pathStr : libraryPaths) {
                Path path = Paths.get(pathStr);
                if (Files.exists(path) && Files.isDirectory(path)) {
                    scanDirectory(path, supportedExtensions, newLibrary);
                } else {
                    System.err.println("⚠️  Invalid library path: " + pathStr);
                }
            }

            // 一次性原子换引用：读者要么看到旧库，要么看到新库，绝不会看到半空状态
            musicLibrary = newLibrary;
            lastScanTime = System.currentTimeMillis();

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println("✅ Scan complete. Found " + musicLibrary.size() + " music files in " + duration + "s");
        } catch (Exception e) {
            System.err.println("❌ Error scanning library: " + e.getMessage());
        } finally {
            scanning.set(false);
        }
    }

    private void scanDirectory(Path directory, List<String> extensions, Map<String, MusicFile> library) {
        try (var stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> isSupportedExtension(path, extensions))
                    .forEach(path -> {
                        try {
                            MusicFile musicFile = createMusicFile(path);
                            if (musicFile != null) {
                                library.put(musicFile.getId(), musicFile);
                            }
                        } catch (Exception e) {
                            // Skip problematic files
                        }
                    });
        } catch (Exception e) {
            System.err.println("❌ Error scanning directory: " + directory + " - " + e.getMessage());
        }
    }

    private boolean isSupportedExtension(Path path, List<String> extensions) {
        String ext = FilenameUtils.getExtension(path.toString()).toLowerCase();
        return extensions.stream().anyMatch(ext::equalsIgnoreCase);
    }

    private MusicFile createMusicFile(Path path) {
        try {
            File file = path.toFile();
            String absolutePath = file.getAbsolutePath();

            // ✅ 用绝对路径生成稳定 ID（重启/重新扫描不变）
            String id = UUID.nameUUIDFromBytes(absolutePath.getBytes(StandardCharsets.UTF_8)).toString();

            String fileName = file.getName();
            String extension = FilenameUtils.getExtension(fileName);
            long fileSize = file.length();
            LocalDateTime lastModified = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(),
                    ZoneId.systemDefault()
            );

            MusicFile musicFile = new MusicFile(
                    id, fileName, absolutePath, fileSize, extension, lastModified
            );

            musicFile.setUrl("/api/stream/" + id);

            // 提取元数据与时长（Jaudiotagger，覆盖 mp3/flac/m4a/ogg/wav 等；失败自动兜底）
            try {
                musicFile.setMetadata(MetadataExtractor.extract(file));
            } catch (Exception e) {
                // 单个文件元数据解析失败不影响其入库
            }

            return musicFile;
        } catch (Exception e) {
            return null;
        }
    }

    public List<MusicFile> getAllMusic() {
        return new ArrayList<>(musicLibrary.values());
    }

    public Optional<MusicFile> getMusicById(String id) {
        return Optional.ofNullable(musicLibrary.get(id));
    }

    public List<MusicFile> searchMusic(String query) {
        String lowerQuery = query.toLowerCase();
        Map<String, MusicFile> snapshot = musicLibrary;
        return snapshot.values().stream()
                .filter(file -> {
                    String fileName = file.getFileName().toLowerCase();
                    MusicMetadata metadata = file.getMetadata();
                    if (metadata != null) {
                        return fileName.contains(lowerQuery) ||
                                (metadata.getTitle() != null && metadata.getTitle().toLowerCase().contains(lowerQuery)) ||
                                (metadata.getArtist() != null && metadata.getArtist().toLowerCase().contains(lowerQuery)) ||
                                (metadata.getAlbum() != null && metadata.getAlbum().toLowerCase().contains(lowerQuery));
                    }
                    return fileName.contains(lowerQuery);
                })
                .collect(Collectors.toList());
    }

    public List<MusicFile> getMusicByArtist(String artist) {
        Map<String, MusicFile> snapshot = musicLibrary;
        return snapshot.values().stream()
                .filter(file -> {
                    MusicMetadata metadata = file.getMetadata();
                    return metadata != null &&
                            metadata.getArtist() != null &&
                            metadata.getArtist().equalsIgnoreCase(artist);
                })
                .collect(Collectors.toList());
    }

    public Map<String, List<MusicFile>> getArtists() {
        Map<String, MusicFile> snapshot = musicLibrary;
        return snapshot.values().stream()
                .filter(file -> file.getMetadata() != null && file.getMetadata().getArtist() != null)
                .filter(file -> !file.getMetadata().getArtist().equals("Unknown Artist"))
                .collect(Collectors.groupingBy(
                        file -> file.getMetadata().getArtist(),
                        Collectors.toList()
                ));
    }

    public int getLibrarySize() {
        return musicLibrary.size();
    }

    public boolean isScanning() {
        return scanning.get();
    }

    public long getLastScanTime() {
        return lastScanTime;
    }
}