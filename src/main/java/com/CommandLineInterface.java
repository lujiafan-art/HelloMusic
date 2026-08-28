package com;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

@Component
public class CommandLineInterface implements CommandLineRunner {
    private final MusicScannerService scannerService;
    private final BroadcastService broadcastService;
    private final ConfigManager configManager;
    private boolean running = true;

    @Autowired
    public CommandLineInterface(MusicScannerService scannerService,
                                BroadcastService broadcastService,
                                ConfigManager configManager) {
        this.scannerService = scannerService;
        this.broadcastService = broadcastService;
        this.configManager = configManager;
    }

    @Override
    public void run(String... args) {
        System.out.println("\n🎵 HelloMusic CLI ready! Type 'help' for commands.\n");

        Thread cliThread = new Thread(this::processCommands);
        cliThread.setDaemon(true);
        cliThread.start();
    }

    private void processCommands() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (running) {
                System.out.print("🎵> ");
                String input = scanner.nextLine().trim();
                handleCommand(input);
            }
        }
    }

    private void handleCommand(String input) {
        if (input.isEmpty()) return;

        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "help", "?" -> showHelp();
            case "list" -> listMusic(args);
            case "search" -> searchMusic(args);
            case "artists" -> listArtists();
            case "stats" -> showStats();
            case "scan" -> triggerScan();
            case "broadcast" -> broadcastNow();
            case "config" -> showConfig();
            case "addpath" -> addLibraryPath(args);
            case "removepath" -> removeLibraryPath(args);
            case "exit", "quit" -> exit();
            default -> System.out.println("❌ Unknown command. Type 'help' for available commands.");
        }
    }

    private void showHelp() {
        System.out.println("""
            \n╔═══════════════════════════════════════════════╗
            ║     🎵 HelloMusic - Available Commands      ║
            ╠═══════════════════════════════════════════════╣
            ║ help, ?         - Show this help message    ║
            ║ list [count]    - List music files          ║
            ║ search <query>  - Search for music          ║
            ║ artists         - List all artists          ║
            ║ stats           - Show statistics           ║
            ║ scan            - Scan library              ║
            ║ broadcast       - Send broadcast            ║
            ║ config          - Show configuration        ║
            ║ addpath <path>  - Add library path          ║
            ║ removepath <idx>- Remove library path       ║
            ║ exit, quit      - Exit application          ║
            ╚═══════════════════════════════════════════════╝
            """);
    }

    private void listMusic(String args) {
        List<MusicFile> allMusic = scannerService.getAllMusic();
        if (allMusic.isEmpty()) {
            System.out.println("📭 No music files found.");
            return;
        }

        int limit = 20;
        if (!args.isEmpty()) {
            try {
                limit = Integer.parseInt(args);
            } catch (NumberFormatException e) {
                System.out.println("⚠️  Invalid number, showing first 20 files.");
            }
        }

        System.out.println("📁 Found " + allMusic.size() + " music files:");
        allMusic.stream()
                .limit(limit)
                .forEach(file -> {
                    String info = file.getFileName();
                    if (file.getMetadata() != null) {
                        String title = file.getMetadata().getTitle();
                        String artist = file.getMetadata().getArtist();
                        if (title != null && !title.isEmpty() && !title.equals("Unknown Title")) {
                            info = title;
                            if (artist != null && !artist.equals("Unknown Artist")) {
                                info += " - " + artist;
                            }
                        }
                    }
                    String duration = "";
                    if (file.getMetadata() != null && file.getMetadata().getDuration() > 0) {
                        int minutes = file.getMetadata().getDuration() / 60;
                        int seconds = file.getMetadata().getDuration() % 60;
                        duration = String.format("[%02d:%02d] ", minutes, seconds);
                    }
                    System.out.println("  " + duration + "🎵 " + info + " (" + file.getExtension() + ")");
                });

        if (allMusic.size() > limit) {
            System.out.println("  ... and " + (allMusic.size() - limit) + " more files.");
        }
    }

    private void searchMusic(String query) {
        if (query.isEmpty()) {
            System.out.println("❌ Please provide a search query.");
            return;
        }

        List<MusicFile> results = scannerService.searchMusic(query);
        if (results.isEmpty()) {
            System.out.println("🔍 No results found for: " + query);
            return;
        }

        System.out.println("🔍 Found " + results.size() + " results:");
        results.forEach(file -> {
            String info = file.getFileName();
            if (file.getMetadata() != null) {
                String title = file.getMetadata().getTitle();
                String artist = file.getMetadata().getArtist();
                if (title != null && !title.isEmpty() && !title.equals("Unknown Title")) {
                    info = title;
                    if (artist != null && !artist.equals("Unknown Artist")) {
                        info += " - " + artist;
                    }
                }
            }
            System.out.println("  🎵 " + info);
        });
    }

    private void listArtists() {
        var artists = scannerService.getArtists();
        if (artists.isEmpty()) {
            System.out.println("📭 No artists found.");
            return;
        }

        System.out.println("🎤 Artists (" + artists.size() + "):");
        artists.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String artist = entry.getKey();
                    List<MusicFile> files = entry.getValue();
                    System.out.println("  🎤 " + artist + " (" + files.size() + " songs)");
                    files.stream().limit(3).forEach(file -> {
                        String title = file.getMetadata() != null && file.getMetadata().getTitle() != null
                                ? file.getMetadata().getTitle()
                                : file.getFileName();
                        if (!title.equals("Unknown Title")) {
                            System.out.println("      🎵 " + title);
                        }
                    });
                    if (files.size() > 3) {
                        System.out.println("      ... and " + (files.size() - 3) + " more");
                    }
                });
    }

    private void showStats() {
        ConfigManager.Config config = configManager.getConfig();
        System.out.println("\n╔═══════════════════════════════════════╗");
        System.out.println("║     📊 HelloMusic Statistics       ║");
        System.out.println("╠═══════════════════════════════════════╣");
        System.out.println("║ Total Files:    " + String.format("%-20d", scannerService.getLibrarySize()) + "║");
        System.out.println("║ Status:         " + String.format("%-20s", scannerService.isScanning() ? "Scanning..." : "Idle") + "║");
        System.out.println("║ Server Port:    " + String.format("%-20d", config.getServerPort()) + "║");
        System.out.println("║ Broadcast Port: " + String.format("%-20d", config.getBroadcastPort()) + "║");
        System.out.println("║ Broadcast Int:  " + String.format("%-20d", config.getBroadcastInterval()) + "ms║");
        System.out.println("╚═══════════════════════════════════════╝");
        System.out.println("\n📁 Library Paths:");
        config.getMusicLibraryPaths().forEach(path -> System.out.println("  📂 " + path));
        System.out.println("\n🎵 Supported Formats: " + String.join(", ", config.getSupportedExtensions()));
    }

    private void triggerScan() {
        System.out.println("🔄 Starting library scan...");
        scannerService.scanLibrary();
        System.out.println("✅ Scan completed.");
    }

    private void broadcastNow() {
        System.out.println("📡 Sending broadcast...");
        broadcastService.sendBroadcastNow();
        System.out.println("✅ Broadcast sent.");
    }

    private void showConfig() {
        ConfigManager.Config config = configManager.getConfig();
        System.out.println("\n╔═══════════════════════════════════════╗");
        System.out.println("║      ⚙️ HelloMusic Configuration    ║");
        System.out.println("╠═══════════════════════════════════════╣");
        System.out.println("║ Server Name:    " + String.format("%-20s", config.getServerName()) + "║");
        System.out.println("║ Server Port:    " + String.format("%-20d", config.getServerPort()) + "║");
        System.out.println("║ Broadcast Port: " + String.format("%-20d", config.getBroadcastPort()) + "║");
        System.out.println("║ Broadcast Int:  " + String.format("%-20d", config.getBroadcastInterval()) + "ms║");
        System.out.println("╚═══════════════════════════════════════╝");
        System.out.println("\n📁 Library Paths:");
        config.getMusicLibraryPaths().forEach(path -> System.out.println("  📂 " + path));
        System.out.println("\n🎵 Supported Formats: " + String.join(", ", config.getSupportedExtensions()));
    }

    private void addLibraryPath(String path) {
        if (path.isEmpty()) {
            System.out.println("❌ Please provide a path.");
            return;
        }

        ConfigManager.Config config = configManager.getConfig();
        List<String> paths = config.getMusicLibraryPaths();
        if (!paths.contains(path)) {
            paths.add(path);
            configManager.saveConfig();
            System.out.println("✅ Added path: " + path);
            System.out.println("🔄 Triggering scan...");
            scannerService.scanLibrary();
        } else {
            System.out.println("⚠️  Path already exists: " + path);
        }
    }

    private void removeLibraryPath(String args) {
        if (args.isEmpty()) {
            System.out.println("❌ Please provide the index of the path to remove.");
            System.out.println("Use 'config' to see the list with indices.");
            return;
        }

        try {
            int index = Integer.parseInt(args);
            ConfigManager.Config config = configManager.getConfig();
            List<String> paths = config.getMusicLibraryPaths();

            if (index >= 0 && index < paths.size()) {
                String removed = paths.remove(index);
                configManager.saveConfig();
                System.out.println("✅ Removed path: " + removed);
                System.out.println("🔄 Triggering scan...");
                scannerService.scanLibrary();
            } else {
                System.out.println("❌ Invalid index. Use 'config' to see the list.");
            }
        } catch (NumberFormatException e) {
            System.out.println("❌ Please provide a valid number.");
        }
    }

    private void exit() {
        System.out.println("\n👋 Goodbye! Thanks for using HelloMusic!");
        running = false;
        System.exit(0);
    }
}