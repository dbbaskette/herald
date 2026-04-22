package com.herald.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Read-only view of the file-based long-term memory directory. Pages are grouped
 * by the {@code type:} frontmatter and surfaced to the Herald Console so humans
 * can browse what the agent has saved. Editing still happens through the agent
 * via {@code AutoMemoryTools} — this controller never writes.
 */
@RestController
@RequestMapping("/api/memory/files")
class FileMemoryController {

    private static final Logger log = LoggerFactory.getLogger(FileMemoryController.class);
    private static final List<String> KNOWN_TYPES = List.of(
            "user", "feedback", "project", "reference",
            "concept", "entity", "source", "unknown");
    private static final long MAX_VIEW_BYTES = 256 * 1024;

    private final Path memoriesRoot;

    FileMemoryController(
            @Value("${herald.ui.memories-path:${HERALD_MEMORIES_DIR:~/.herald/memories}}")
                    String memoriesPath) {
        this.memoriesRoot = resolveTilde(memoriesPath).toAbsolutePath().normalize();
    }

    @GetMapping
    Map<String, List<MemoryPage>> listGroupedByType() {
        Map<String, List<MemoryPage>> grouped = new LinkedHashMap<>();
        for (String t : KNOWN_TYPES) {
            grouped.put(t, new ArrayList<>());
        }

        if (!Files.isDirectory(memoriesRoot)) {
            return grouped;
        }

        try (Stream<Path> walk = Files.walk(memoriesRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(FileMemoryController::isMarkdownPage)
                    .forEach(p -> addPage(grouped, p));
        } catch (IOException e) {
            log.warn("Failed to walk memories dir {}: {}", memoriesRoot, e.getMessage());
        }

        grouped.values().forEach(list ->
                list.sort(Comparator.comparing(MemoryPage::path, String.CASE_INSENSITIVE_ORDER)));
        return grouped;
    }

    @GetMapping("/content")
    ResponseEntity<MemoryPageContent> content(@RequestParam("path") String relPath) {
        Path resolved;
        try {
            resolved = memoriesRoot.resolve(relPath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        if (!resolved.startsWith(memoriesRoot)) {
            return ResponseEntity.badRequest().build();
        }
        if (!Files.isRegularFile(resolved)) {
            return ResponseEntity.notFound().build();
        }
        try {
            long size = Files.size(resolved);
            String body;
            if (size > MAX_VIEW_BYTES) {
                byte[] head = new byte[(int) MAX_VIEW_BYTES];
                try (var in = Files.newInputStream(resolved)) {
                    int n = in.read(head);
                    body = new String(head, 0, Math.max(0, n), StandardCharsets.UTF_8)
                            + "\n\n… (truncated, file is " + size + " bytes)";
                }
            } else {
                body = Files.readString(resolved, StandardCharsets.UTF_8);
            }
            return ResponseEntity.ok(new MemoryPageContent(relPath, body, size));
        } catch (IOException e) {
            log.warn("Failed to read memory page {}: {}", resolved, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    private void addPage(Map<String, List<MemoryPage>> grouped, Path file) {
        try {
            Frontmatter fm = parseFrontmatter(file);
            String type = fm.type() == null ? "unknown" : fm.type().toLowerCase();
            if (!grouped.containsKey(type)) {
                grouped.put("unknown", grouped.getOrDefault("unknown", new ArrayList<>()));
                type = "unknown";
            }
            String rel = memoriesRoot.relativize(file).toString().replace('\\', '/');
            if (rel.equals("MEMORY.md") || rel.equals("log.md") || rel.equals("hot.md")) {
                return;
            }
            long size = Files.size(file);
            String lastModified = Files.getLastModifiedTime(file).toInstant().toString();
            grouped.get(type).add(new MemoryPage(
                    rel, fm.name(), fm.description(), type, size, lastModified));
        } catch (IOException e) {
            log.debug("Skipping memory file {}: {}", file, e.getMessage());
        }
    }

    static boolean isMarkdownPage(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".md");
    }

    static Frontmatter parseFrontmatter(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
            return new Frontmatter(null, null, null);
        }
        String name = null, description = null, type = null;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().equals("---")) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            switch (key) {
                case "name" -> name = value;
                case "description" -> description = value;
                case "type" -> type = value;
                default -> { /* ignore other keys */ }
            }
        }
        return new Frontmatter(name, description, type);
    }

    private static Path resolveTilde(String raw) {
        if (raw == null || raw.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".herald", "memories");
        }
        if (raw.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), raw.substring(2));
        }
        return Path.of(raw);
    }

    record MemoryPage(
            String path,
            String name,
            String description,
            String type,
            long size,
            String lastModified) {
    }

    record MemoryPageContent(String path, String content, long size) {
    }

    record Frontmatter(String name, String description, String type) {
    }
}
