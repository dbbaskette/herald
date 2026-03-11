package com.herald.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.herald.ui.config.HeraldUiConfig;
import com.herald.ui.sse.StatusSseService;

import org.yaml.snakeyaml.Yaml;

@RestController
@RequestMapping("/api/skills")
class SkillsController {

    private final Path skillsDir;
    private final Path bundledSkillsDir;
    private final StatusSseService statusSseService;

    SkillsController(HeraldUiConfig config, StatusSseService statusSseService) {
        this.skillsDir = resolvePath(config.skillsPath());
        String bundledPath = config.bundledSkillsPath();
        this.bundledSkillsDir = (bundledPath != null && !bundledPath.isBlank())
                ? resolvePath(bundledPath) : null;
        this.statusSseService = statusSseService;
    }

    @GetMapping
    List<SkillSummary> list() throws IOException {
        List<SkillSummary> skills = new ArrayList<>();
        // Collect local skills first so we can deduplicate bundled ones
        java.util.Set<String> localNames = new java.util.HashSet<>();
        if (Files.isDirectory(skillsDir)) {
            collectSkills(skillsDir, "local", false, skills);
            skills.forEach(s -> localNames.add(s.name()));
        }
        if (bundledSkillsDir != null && Files.isDirectory(bundledSkillsDir)) {
            List<SkillSummary> bundled = new ArrayList<>();
            collectSkills(bundledSkillsDir, "bundled", true, bundled);
            // Only include bundled skills that don't have a local copy
            bundled.stream().filter(s -> !localNames.contains(s.name())).forEach(skills::add);
        }
        skills.sort(Comparator.comparing(SkillSummary::name));
        return skills;
    }

    @GetMapping(value = "/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> get(@PathVariable String name) throws IOException {
        if (!isValidName(name)) {
            return ResponseEntity.badRequest().build();
        }
        Path skillFile = resolveSkillFile(name);
        if (skillFile == null || !Files.exists(skillFile)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Files.readString(skillFile));
    }

    @PutMapping("/{name}")
    ResponseEntity<Void> update(@PathVariable String name, @RequestBody String content)
            throws IOException {
        if (!isValidName(name)) {
            return ResponseEntity.badRequest().build();
        }
        if (isBundled(name)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Path skillDir = skillsDir.resolve(name);
        if (!Files.isDirectory(skillDir)) {
            return ResponseEntity.notFound().build();
        }
        Files.writeString(skillDir.resolve("SKILL.md"), content);
        statusSseService.publishSkillReload(Instant.now().toString());
        return ResponseEntity.ok().build();
    }

    @PostMapping
    ResponseEntity<Void> create(@RequestBody SkillCreatePayload payload) throws IOException {
        if (!isValidName(payload.name())) {
            return ResponseEntity.badRequest().build();
        }
        Path skillDir = skillsDir.resolve(payload.name());
        if (Files.exists(skillDir)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Files.createDirectories(skillDir);
        String template = """
                ---
                name: %s
                description: >
                  TODO: Describe what this skill does and when it should be used.
                ---

                # %s

                Add your skill instructions here.
                """.formatted(payload.name(), capitalize(payload.name()));
        Files.writeString(skillDir.resolve("SKILL.md"), template);
        statusSseService.publishSkillReload(Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{name}")
    ResponseEntity<Void> delete(@PathVariable String name) throws IOException {
        if (!isValidName(name)) {
            return ResponseEntity.badRequest().build();
        }
        if (isBundled(name)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Path skillDir = skillsDir.resolve(name);
        if (!Files.exists(skillDir)) {
            return ResponseEntity.notFound().build();
        }
        deleteRecursively(skillDir);
        statusSseService.publishSkillReload(Instant.now().toString());
        return ResponseEntity.noContent().build();
    }

    private void collectSkills(Path dir, String source, boolean readOnly,
            List<SkillSummary> skills) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(skillDir -> {
                Path skillFile = skillDir.resolve("SKILL.md");
                if (Files.exists(skillFile)) {
                    try {
                        String content = Files.readString(skillFile);
                        Map<String, String> frontmatter = parseFrontmatter(content);
                        String name = frontmatter.getOrDefault("name",
                                skillDir.getFileName().toString());
                        String description = frontmatter.getOrDefault("description", "");
                        skills.add(new SkillSummary(name, description, source, readOnly));
                    }
                    catch (IOException e) {
                        // skip unreadable skills
                    }
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> parseFrontmatter(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        if (content == null || !content.startsWith("---")) {
            return result;
        }
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            reader.readLine(); // skip opening ---
            StringBuilder yaml = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("---")) {
                    break;
                }
                yaml.append(line).append("\n");
            }
            if (!yaml.isEmpty()) {
                Yaml yamlParser = new Yaml();
                Map<String, Object> parsed = yamlParser.load(yaml.toString());
                if (parsed != null) {
                    parsed.forEach((k, v) -> {
                        if (v != null) {
                            result.put(k, v.toString().strip());
                        }
                    });
                }
            }
        }
        catch (IOException e) {
            // should not happen with StringReader
        }
        return result;
    }

    private Path resolveSkillFile(String name) {
        // Check local first, then bundled
        Path local = skillsDir.resolve(name).resolve("SKILL.md");
        if (Files.exists(local)) {
            return local;
        }
        if (bundledSkillsDir != null) {
            Path bundled = bundledSkillsDir.resolve(name).resolve("SKILL.md");
            if (Files.exists(bundled)) {
                return bundled;
            }
        }
        return null;
    }

    private boolean isBundled(String name) {
        return bundledSkillsDir != null
                && Files.exists(bundledSkillsDir.resolve(name).resolve("SKILL.md"));
    }

    private boolean isValidName(String name) {
        return name != null && name.matches("[a-zA-Z0-9_-]+");
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                }
                catch (IOException e) {
                    // best effort
                }
            });
        }
    }

    private static Path resolvePath(String path) {
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return Path.of(path);
    }

    private static String capitalize(String name) {
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    record SkillSummary(String name, String description, String source, boolean readOnly) {
    }

    record SkillCreatePayload(String name) {
    }
}
