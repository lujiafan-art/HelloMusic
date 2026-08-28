package com;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HelloMusicApplication {
    public static void main(String[] args) {
        SpringApplication.run(HelloMusicApplication.class, args);
        System.out.println("\n");
        System.out.println("╔═══════════════════════════════════════════╗");
        System.out.println("║     🎵 HelloMusic Server Started 🎵      ║");
        System.out  .println("║                                           ║");
        System.out.println("║  API: http://localhost:8080/api          ║");
        System.out.println("║  CLI: Type 'help' in console            ║");
        System.out.println("║  Broadcast: UDP 9999                    ║");
        System.out.println("╚═══════════════════════════════════════════╝");
        System.out.println("\n");
    }
}