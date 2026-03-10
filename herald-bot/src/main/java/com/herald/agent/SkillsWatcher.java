package com.herald.agent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches the skills directory for filesystem changes and triggers a hot-reload
 * on {@link ReloadableSkillsTool} within 250ms using debounced events.
 */
@Component
public class SkillsWatcher {

    private static final Logger log = LoggerFactory.getLogger(SkillsWatcher.class);
    private static final long DEBOUNCE_MS = 250;

    private final ReloadableSkillsTool reloadableSkillsTool;
    private final String skillsDirectory;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skills-watcher-debounce");
        t.setDaemon(true);
        return t;
    });

    private volatile WatchService watchService;
    private volatile ScheduledFuture<?> pendingReload;
    private final Map<WatchKey, Path> watchKeyPaths = new HashMap<>();

    public SkillsWatcher(ReloadableSkillsTool reloadableSkillsTool,
                         @Value("${herald.agent.skills-directory:.claude/skills}") String skillsDirectory) {
        this.reloadableSkillsTool = reloadableSkillsTool;
        this.skillsDirectory = skillsDirectory;
    }

    @PostConstruct
    void startWatching() {
        Path skillsPath = Path.of(skillsDirectory);
        if (!Files.isDirectory(skillsPath)) {
            log.warn("Skills directory {} does not exist; file watching is disabled", skillsDirectory);
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerRecursive(skillsPath);
            log.info("Skills watcher started on {}", skillsDirectory);
        } catch (IOException e) {
            log.error("Failed to initialize skills watcher on {}: {}", skillsDirectory, e.getMessage(), e);
            return;
        }

        Thread watchThread = new Thread(this::watchLoop, "skills-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void registerRecursive(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                watchKeyPaths.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void watchLoop() {
        while (watchService != null) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                Thread.currentThread().interrupt();
                return;
            }

            Path dir = watchKeyPaths.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == OVERFLOW) {
                    continue;
                }

                Path changed = (dir != null) ? dir.resolve((Path) event.context()) : (Path) event.context();
                String eventType = eventTypeLabel(kind);
                String skillName = changed.getFileName().toString();

                log.info("Skill file change detected: name={}, event={}, timestamp={}", skillName, eventType, Instant.now());

                // Register new subdirectories for recursive watching
                if (kind == ENTRY_CREATE && Files.isDirectory(changed)) {
                    try {
                        registerRecursive(changed);
                    } catch (IOException e) {
                        log.warn("Failed to register watcher for new subdirectory {}: {}", changed, e.getMessage());
                    }
                }

                scheduleReload();
            }

            boolean valid = key.reset();
            if (!valid) {
                watchKeyPaths.remove(key);
                if (watchKeyPaths.isEmpty()) {
                    log.warn("All watched directories are gone; stopping skills watcher");
                    break;
                }
            }
        }
    }

    /** Package-private for testing. */
    void scheduleReload() {
        ScheduledFuture<?> existing = pendingReload;
        if (existing != null) {
            existing.cancel(false);
        }
        pendingReload = scheduler.schedule(() -> {
            log.info("Reloading skills after file change");
            int count = reloadableSkillsTool.reload();
            log.info("Skills reloaded: {} skill(s) available", count);
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private static String eventTypeLabel(WatchEvent.Kind<?> kind) {
        if (kind == ENTRY_CREATE) return "added";
        if (kind == ENTRY_MODIFY) return "updated";
        if (kind == ENTRY_DELETE) return "deleted";
        return "unknown";
    }

    @PreDestroy
    void stopWatching() {
        scheduler.shutdownNow();
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("Error closing watch service: {}", e.getMessage());
            }
        }
        log.info("Skills watcher stopped");
    }
}
