package com.herald.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/obsidian")
class ObsidianController {

    private static final Logger log = LoggerFactory.getLogger(ObsidianController.class);
    private static final String OBSIDIAN_CLI = "/Applications/Obsidian.app/Contents/MacOS/obsidian";
    private static final String VAULT = "Herald-Memory";

    @GetMapping("/search")
    ResponseEntity<List<SearchResult>> search(
            @RequestParam String query,
            @RequestParam(required = false) String folder) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(OBSIDIAN_CLI);
            cmd.add("search:context");
            cmd.add("vault=" + VAULT);
            cmd.add("query=" + query);
            if (folder != null && !folder.isBlank()) {
                cmd.add("path=" + folder);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ResponseEntity.internalServerError().build();
            }

            List<SearchResult> results = parseContextResults(lines);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Obsidian search failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/read")
    ResponseEntity<NoteContent> read(@RequestParam String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    OBSIDIAN_CLI, "read", "vault=" + VAULT, "path=" + path);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ResponseEntity.internalServerError().build();
            }

            if (process.exitValue() != 0) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(new NoteContent(path, sb.toString()));
        } catch (Exception e) {
            log.error("Obsidian read failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/folders")
    ResponseEntity<List<String>> folders() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    OBSIDIAN_CLI, "folders", "vault=" + VAULT);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<String> folderList = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.equals("/")) {
                        folderList.add(trimmed);
                    }
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ResponseEntity.internalServerError().build();
            }

            return ResponseEntity.ok(folderList);
        } catch (Exception e) {
            log.error("Obsidian folders failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Parse search:context output format: "path/to/note.md:lineNum: matched text"
     * Groups results by file path, collecting snippets.
     */
    private static List<SearchResult> parseContextResults(List<String> lines) {
        // Pattern: file.md:line: text
        Pattern pattern = Pattern.compile("^(.+\\.md):(\\d+):\\s*(.*)$");
        // Track results by path, preserving order
        List<SearchResult> results = new ArrayList<>();
        List<String> currentPaths = new ArrayList<>();

        for (String line : lines) {
            Matcher m = pattern.matcher(line);
            if (m.matches()) {
                String path = m.group(1);
                String snippet = m.group(3).trim();
                int idx = currentPaths.indexOf(path);
                if (idx >= 0) {
                    results.get(idx).snippets().add(snippet);
                } else {
                    currentPaths.add(path);
                    List<String> snippets = new ArrayList<>();
                    snippets.add(snippet);
                    String folder = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
                    String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                    if (name.endsWith(".md")) name = name.substring(0, name.length() - 3);
                    results.add(new SearchResult(path, name, folder, snippets));
                }
            } else if (!line.isBlank()) {
                // Plain search result (just a path)
                String path = line.trim();
                if (path.endsWith(".md") && !currentPaths.contains(path)) {
                    currentPaths.add(path);
                    String folder = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
                    String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                    if (name.endsWith(".md")) name = name.substring(0, name.length() - 3);
                    results.add(new SearchResult(path, name, folder, new ArrayList<>()));
                }
            }
        }
        return results;
    }

    record SearchResult(String path, String name, String folder, List<String> snippets) {
    }

    record NoteContent(String path, String content) {
    }
}
