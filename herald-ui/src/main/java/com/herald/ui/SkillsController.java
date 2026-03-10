package com.herald.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/skills")
class SkillsController {

    private final Path skillsDir;

    SkillsController(HeraldUiConfig config) {
        String path = config.skillsPath();
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        this.skillsDir = Path.of(path);
    }

    @GetMapping
    List<String> list() throws IOException {
        if (!Files.exists(skillsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(skillsDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return name.substring(0, name.length() - 3);
                    })
                    .sorted()
                    .toList();
        }
    }

    @GetMapping("/{name}")
    ResponseEntity<SkillContent> get(@PathVariable String name) throws IOException {
        if (!isValidName(name)) {
            return ResponseEntity.badRequest().build();
        }
        Path file = skillsDir.resolve(name + ".md");
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        String content = Files.readString(file);
        return ResponseEntity.ok(new SkillContent(name, content));
    }

    @PutMapping("/{name}")
    ResponseEntity<SkillContent> update(@PathVariable String name, @RequestBody SkillPayload payload)
            throws IOException {
        if (!isValidName(name)) {
            return ResponseEntity.badRequest().build();
        }
        ensureSkillsDir();
        Path file = skillsDir.resolve(name + ".md");
        Files.writeString(file, payload.content());
        return ResponseEntity.ok(new SkillContent(name, payload.content()));
    }

    @PostMapping
    ResponseEntity<SkillContent> create(@RequestBody SkillCreatePayload payload) throws IOException {
        if (!isValidName(payload.name())) {
            return ResponseEntity.badRequest().build();
        }
        ensureSkillsDir();
        Path file = skillsDir.resolve(payload.name() + ".md");
        if (Files.exists(file)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Files.writeString(file, payload.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SkillContent(payload.name(), payload.content()));
    }

    @DeleteMapping("/{name}")
    ResponseEntity<Void> delete(@PathVariable String name) throws IOException {
        if (!isValidName(name)) {
            return ResponseEntity.badRequest().build();
        }
        Path file = skillsDir.resolve(name + ".md");
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        Files.delete(file);
        return ResponseEntity.noContent().build();
    }

    private boolean isValidName(String name) {
        return name != null && name.matches("[a-zA-Z0-9_-]+");
    }

    private void ensureSkillsDir() throws IOException {
        if (!Files.exists(skillsDir)) {
            Files.createDirectories(skillsDir);
        }
    }

    record SkillContent(String name, String content) {
    }

    record SkillPayload(String content) {
    }

    record SkillCreatePayload(String name, String content) {
    }
}
