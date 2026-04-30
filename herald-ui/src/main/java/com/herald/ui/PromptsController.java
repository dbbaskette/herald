package com.herald.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints powering the Prompts page in the web console. Lists the
 * bundled Herald system prompts alongside user-editable files
 * ({@code ~/.herald/CONTEXT.md}), serves their content, and persists user
 * overrides to {@code ~/.herald/prompts/&lt;name&gt;.md}. Bundled prompts on
 * the bot side use {@link com.herald.agent.PromptLoader} which checks the
 * same override directory at startup, so saved edits take effect on the
 * next bot restart.
 */
@RestController
@RequestMapping("/api/prompts")
class PromptsController {

    private static final Logger log = LoggerFactory.getLogger(PromptsController.class);

    /**
     * Bundled system prompts. Order here drives the order shown in the UI.
     * Description is the human-friendly explanation surfaced in the editor.
     */
    private static final List<BundledPrompt> BUNDLED = List.of(
            new BundledPrompt(
                    "MAIN_AGENT_SYSTEM_PROMPT.md",
                    "Main Agent System Prompt",
                    "The primary instructions Herald gets every turn. Defines persona, tool-use discipline, "
                            + "memory rules, communication style. Edits take effect on next bot restart."),
            new BundledPrompt(
                    "TASK_MANAGEMENT_GUIDANCE.md",
                    "Task Management Guidance",
                    "Shared agentic-loop guidance injected into both Herald's main prompt and ephemeral "
                            + "agents.md system prompts. Covers todoWrite usage, parallelization, and tool discipline."),
            new BundledPrompt(
                    "AUTO_MEMORY_SYSTEM_PROMPT.md",
                    "Auto-Memory System Prompt",
                    "Instructions for the memory advisor — when to save user/feedback/project notes, "
                            + "what NOT to store, the two-step Create+Insert pattern."),
            new BundledPrompt(
                    "RETROSPECTIVE_PROMPT.md",
                    "Retrospective Prompt (/why)",
                    "Template the /why command uses to ask Herald to explain its previous turn with extended thinking."),
            new BundledPrompt(
                    "CONTEXT_TEMPLATE.md",
                    "Context Template",
                    "Seed for ~/.herald/CONTEXT.md on first run. Editing this only affects new installs.")
    );

    private static final String CONTEXT_NAME = "CONTEXT.md";
    private static final String CONTEXT_DISPLAY = "Personal Context (CONTEXT.md)";
    private static final String CONTEXT_DESCRIPTION =
            "Your personal context, loaded into every turn via ContextMdAdvisor. The right place for stable "
            + "instructions like 'auto-process documents with markitdown', preferences, ongoing projects, "
            + "and people. Edits take effect on the next message — no restart needed.";

    private final Path contextFile;
    private final Path overrideDir;

    PromptsController(@Value("${herald.agent.context-file:~/.herald/CONTEXT.md}") String contextFile) {
        this.contextFile = expand(contextFile);
        this.overrideDir = resolveOverrideDir();
    }

    @GetMapping
    List<PromptSummary> list() {
        List<PromptSummary> out = new ArrayList<>();

        // CONTEXT.md is always first — that's where users write their standing instructions.
        out.add(new PromptSummary(
                CONTEXT_NAME,
                CONTEXT_DISPLAY,
                CONTEXT_DESCRIPTION,
                "user",
                Files.exists(contextFile),
                contextFile.toString(),
                true));

        for (BundledPrompt p : BUNDLED) {
            Path override = overrideDir.resolve(p.name());
            boolean hasOverride = Files.isRegularFile(override);
            out.add(new PromptSummary(
                    p.name(),
                    p.displayName(),
                    p.description(),
                    hasOverride ? "user-override" : "bundled",
                    hasOverride,
                    hasOverride ? override.toString() : null,
                    true));
        }
        return out;
    }

    @GetMapping("/{name}")
    ResponseEntity<PromptDetail> read(@PathVariable String name) {
        if (CONTEXT_NAME.equals(name)) {
            String content = readSafe(contextFile);
            String defaultContent = "";
            return ResponseEntity.ok(new PromptDetail(
                    name, CONTEXT_DISPLAY, CONTEXT_DESCRIPTION,
                    content, defaultContent, "user",
                    Files.exists(contextFile),
                    contextFile.toString()));
        }

        BundledPrompt p = findBundled(name);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        String defaultContent = readClasspath("prompts/" + p.name());
        Path override = overrideDir.resolve(p.name());
        boolean overridden = Files.isRegularFile(override);
        String content = overridden ? readSafe(override) : defaultContent;
        return ResponseEntity.ok(new PromptDetail(
                p.name(), p.displayName(), p.description(),
                content, defaultContent,
                overridden ? "user-override" : "bundled",
                overridden,
                override.toString()));
    }

    @PutMapping(value = "/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> save(
            @PathVariable String name,
            @RequestBody PromptUpdate body) {
        String content = body.content() != null ? body.content() : "";

        if (CONTEXT_NAME.equals(name)) {
            try {
                ensureParent(contextFile);
                Files.writeString(contextFile, content, StandardCharsets.UTF_8);
                log.info("Updated personal context at {}", contextFile);
                return ResponseEntity.ok(Map.of(
                        "saved", true,
                        "path", contextFile.toString(),
                        "restartRequired", false));
            } catch (IOException e) {
                log.warn("Failed to save CONTEXT.md: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("saved", false, "error", e.getMessage()));
            }
        }

        BundledPrompt p = findBundled(name);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        Path target = overrideDir.resolve(p.name());
        try {
            ensureParent(target);
            Files.writeString(target, content, StandardCharsets.UTF_8);
            log.info("Saved prompt override to {}", target);
            return ResponseEntity.ok(Map.of(
                    "saved", true,
                    "path", target.toString(),
                    "restartRequired", true));
        } catch (IOException e) {
            log.warn("Failed to save prompt override {}: {}", name, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("saved", false, "error", e.getMessage()));
        }
    }

    /** Delete a user override, reverting to the bundled default on next restart. */
    @DeleteMapping("/{name}")
    ResponseEntity<Map<String, Object>> revert(@PathVariable String name) {
        if (CONTEXT_NAME.equals(name)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "CONTEXT.md is user-owned; clear its content via PUT instead."));
        }
        BundledPrompt p = findBundled(name);
        if (p == null) return ResponseEntity.notFound().build();
        Path target = overrideDir.resolve(p.name());
        try {
            boolean deleted = Files.deleteIfExists(target);
            return ResponseEntity.ok(Map.of(
                    "reverted", deleted,
                    "path", target.toString(),
                    "restartRequired", deleted));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("reverted", false, "error", e.getMessage()));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static BundledPrompt findBundled(String name) {
        for (BundledPrompt p : BUNDLED) {
            if (p.name().equals(name)) return p;
        }
        return null;
    }

    private static String readSafe(Path path) {
        if (!Files.isRegularFile(path)) return "";
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", path, e.getMessage());
            return "";
        }
    }

    private static String readClasspath(String location) {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read classpath:{} — {}", location, e.getMessage());
            return "";
        }
    }

    private static void ensureParent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static Path expand(String raw) {
        if (raw.startsWith("~")) {
            return Paths.get(System.getProperty("user.home") + raw.substring(1));
        }
        return Paths.get(raw);
    }

    private static Path resolveOverrideDir() {
        String heraldHome = System.getenv("HERALD_HOME");
        Path home = (heraldHome != null && !heraldHome.isBlank())
                ? Paths.get(heraldHome)
                : Paths.get(System.getProperty("user.home"), ".herald");
        return home.resolve("prompts");
    }

    private record BundledPrompt(String name, String displayName, String description) {}

    public record PromptSummary(
            String name,
            String displayName,
            String description,
            String source,        // "user" | "user-override" | "bundled"
            boolean overridden,
            String overridePath,
            boolean editable
    ) {}

    public record PromptDetail(
            String name,
            String displayName,
            String description,
            String content,         // current effective content
            String defaultContent,  // bundled version (empty for user-owned files)
            String source,
            boolean overridden,
            String overridePath
    ) {}

    public record PromptUpdate(String content) {}
}
