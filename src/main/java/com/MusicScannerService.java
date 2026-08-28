package com;

import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class MusicScannerService {
    private final Map<String, MusicFile> musicLibrary = new ConcurrentHashMap<>();
    private final ConfigManager configManager;
    private boolean scanning = false;
    private long lastScanTime = 0;

    @Autowired
    public MusicScannerService(ConfigManager configManager) {
        this.configManager = configManager;
        scanLibrary();
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 300000)
    public void scanLibrary() {
        if (scanning) return;
        scanning = true;

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

            musicLibrary.clear();
            musicLibrary.putAll(newLibrary);
            lastScanTime = System.currentTimeMillis();

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println("✅ Scan complete. Found " + musicLibrary.size() + " music files in " + duration + "s");
        } catch (Exception e) {
            System.err.println("❌ Error scanning library: " + e.getMessage());
        } finally {
            scanning = false;
        }
    }

    private void scanDirectory(Path directory, List<String> extensions, Map<String, MusicFile> library) {
        try {
            Files.walk(directory)
                    .filter(Files::isRegularFile)
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
            String id = UUID.randomUUID().toString();
            String fileName = file.getName();
            String extension = FilenameUtils.getExtension(fileName);
            long fileSize = file.length();
            LocalDateTime lastModified = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(),
                    ZoneId.systemDefault()
            );

            MusicFile musicFile = new MusicFile(
                    id, fileName, path.toString(), fileSize, extension, lastModified
            );

            musicFile.setUrl("/api/stream/" + id);

            if (extension.equalsIgnoreCase("mp3")) {
                try {
                    MusicMetadata metadata = extractMp3Metadata(file);
                    musicFile.setMetadata(metadata);
                } catch (Exception e) {
                    // Metadata extraction failed
                }
            }

            return musicFile;
        } catch (Exception e) {
            return null;
        }
    }

    private MusicMetadata extractMp3Metadata(File file) throws IOException {
        MusicMetadata metadata = new MusicMetadata();

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLength = raf.length();

            if (fileLength >= 128) {
                raf.seek(fileLength - 128);
                byte[] tagBytes = new byte[128];
                raf.readFully(tagBytes);

                String tagHeader = new String(tagBytes, 0, 3, StandardCharsets.ISO_8859_1);
                if ("TAG".equals(tagHeader)) {
                    String title = new String(tagBytes, 3, 30, StandardCharsets.ISO_8859_1).trim();
                    metadata.setTitle(title.isEmpty() ? "Unknown Title" : title);

                    String artist = new String(tagBytes, 33, 30, StandardCharsets.ISO_8859_1).trim();
                    metadata.setArtist(artist.isEmpty() ? "Unknown Artist" : artist);

                    String album = new String(tagBytes, 63, 30, StandardCharsets.ISO_8859_1).trim();
                    metadata.setAlbum(album.isEmpty() ? "Unknown Album" : album);

                    String yearStr = new String(tagBytes, 93, 4, StandardCharsets.ISO_8859_1).trim();
                    if (!yearStr.isEmpty()) {
                        try {
                            metadata.setYear(Integer.parseInt(yearStr));
                        } catch (NumberFormatException ignored) {}
                    }

                    String comment = new String(tagBytes, 97, 30, StandardCharsets.ISO_8859_1).trim();
                    metadata.setComment(comment);

                    int genreCode = tagBytes[127] & 0xFF;
                    if (genreCode >= 0 && genreCode < ID3_GENRES.length) {
                        metadata.setGenre(ID3_GENRES[genreCode]);
                    } else {
                        metadata.setGenre("Unknown Genre");
                    }
                } else {
                    String fileName = file.getName();
                    int extIndex = fileName.lastIndexOf('.');
                    String baseName = extIndex > 0 ? fileName.substring(0, extIndex) : fileName;

                    String[] parts = baseName.split(" - ");
                    if (parts.length >= 2) {
                        metadata.setArtist(parts[0].trim());
                        metadata.setTitle(parts[1].trim());
                        metadata.setAlbum("Unknown Album");
                    } else {
                        metadata.setTitle(baseName);
                        metadata.setArtist("Unknown Artist");
                        metadata.setAlbum("Unknown Album");
                    }
                    metadata.setGenre("Unknown Genre");
                }
            } else {
                String fileName = file.getName();
                int extIndex = fileName.lastIndexOf('.');
                String baseName = extIndex > 0 ? fileName.substring(0, extIndex) : fileName;

                String[] parts = baseName.split(" - ");
                if (parts.length >= 2) {
                    metadata.setArtist(parts[0].trim());
                    metadata.setTitle(parts[1].trim());
                } else {
                    metadata.setTitle(baseName);
                    metadata.setArtist("Unknown Artist");
                }
                metadata.setAlbum("Unknown Album");
                metadata.setGenre("Unknown Genre");
            }
        }

        return metadata;
    }

    private static final String[] ID3_GENRES = {
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop",
            "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock",
            "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack",
            "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance",
            "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
            "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop",
            "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic",
            "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
            "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret",
            "New Wave", "Psychadelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal",
            "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
            "Folk", "Folk/Rock", "National Folk", "Swing", "Fast Fusion", "Bebop", "Latin",
            "Revival", "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock",
            "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus", "Easy Listening",
            "Acoustic", "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata",
            "Symphony", "Booty Bass", "Primus", "Porn Groove", "Satire", "Slow Jam", "Club",
            "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
            "Duet", "Punk Rock", "Drum Solo", "Acapella", "Euro-House", "Dance Hall"
    };

    public List<MusicFile> getAllMusic() {
        return new ArrayList<>(musicLibrary.values());
    }

    public Optional<MusicFile> getMusicById(String id) {
        return Optional.ofNullable(musicLibrary.get(id));
    }

    public List<MusicFile> searchMusic(String query) {
        String lowerQuery = query.toLowerCase();
        return musicLibrary.values().stream()
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
        return musicLibrary.values().stream()
                .filter(file -> {
                    MusicMetadata metadata = file.getMetadata();
                    return metadata != null &&
                            metadata.getArtist() != null &&
                            metadata.getArtist().equalsIgnoreCase(artist);
                })
                .collect(Collectors.toList());
    }

    public Map<String, List<MusicFile>> getArtists() {
        return musicLibrary.values().stream()
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
        return scanning;
    }

    public long getLastScanTime() {
        return lastScanTime;
    }
}