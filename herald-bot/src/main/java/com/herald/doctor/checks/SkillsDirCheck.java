package com.herald.doctor.checks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.herald.doctor.HealthCheck;

/**
 * Scans the skills directory, counting valid SKILL.md files and flagging
 * those missing or with malformed YAML frontmatter. A skill that fails to
 * parse loads as empty at runtime — the symptom is "my skill doesn't work
 * but I see no error" until you read the tiny WARN in the skills log.
 */
public class SkillsDirCheck implements HealthCheck {

    private final Path skillsDir;

    public SkillsDirCheck(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    public SkillsDirCheck() {
        this(resolveDefaultPath());
    }

    private static Path resolveDefaultPath() {
        String raw = System.getenv().getOrDefault("HERALD_SKILLS_DIRECTORY", "~/.herald/skills");
        if (raw.startsWith("~/")) {
            raw = System.getProperty("user.home") + raw.substring(1);
        }
        return Path.of(raw);
    }

    @Override
    public String name() {
        return "Skills directory";
    }

    @Override
    public Result run() {
        if (!Files.exists(skillsDir)) {
            return Result.warn("missing at " + skillsDir,
                    "Skills are optional; copy repo's ./skills/ dir here if you want the bundled set");
        }
        if (!Files.isDirectory(skillsDir)) {
            return Result.fail("not a directory: " + skillsDir, null);
        }

        int ok = 0;
        List<String> malformed = new ArrayList<>();
        try (Stream<Path> children = Files.list(skillsDir)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                Path skillMd = child.resolve("SKILL.md");
                if (!Files.exists(skillMd)) {
                    malformed.add(child.getFileName() + " (no SKILL.md)");
                    continue;
                }
                try {
                    String content = Files.readString(skillMd);
                    if (hasValidFrontmatter(content)) {
                        ok++;
                    } else {
                        malformed.add(child.getFileName() + " (bad frontmatter)");
                    }
                } catch (IOException e) {
                    malformed.add(child.getFileName() + " (" + e.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            return Result.fail("scan failed: " + e.getMessage(), null);
        }

        String summary = ok + " skill(s) loaded";
        if (malformed.isEmpty()) {
            return Result.ok(summary);
        }
        return Result.warn(summary + "; malformed: " + malformed,
                "Run `wiki-lint` skill or inspect SKILL.md — frontmatter needs name + description");
    }

    /**
     * Minimal check: file starts with {@code ---}, contains {@code name:} and
     * {@code description:} inside the first frontmatter block. Doesn't need a
     * full YAML parser — a full parser would pull in Snake-YAML on the doctor's
     * cold-start path.
     */
    static boolean hasValidFrontmatter(String content) {
        if (!content.startsWith("---")) return false;
        int end = content.indexOf("\n---", 3);
        if (end < 0) return false;
        String block = content.substring(3, end);
        return block.contains("name:") && block.contains("description:");
    }
}
