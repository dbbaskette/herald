package com.herald.ui;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import com.herald.ui.config.HeraldUiConfig;

@Service
class SkillValidationService {
    static final int MAX_CONTENT = 256 * 1024;
    record Diagnostic(String severity, String code, String message, int line, int column) {}
    record Result(boolean valid, String name, String description, List<Diagnostic> diagnostics,
                  String capabilityStatus, String vaultStatus) {}
    private final Path local, bundled, project, vault;
    private final SkillCapabilities capabilities;
    SkillValidationService(HeraldUiConfig config, SkillCapabilities capabilities,
            @Value("${herald.ui.project-path:.}") String project,
            @Value("${herald.obsidian.vault-path:${HERALD_OBSIDIAN_VAULT_PATH:}}") String vault) {
        this.local = path(config.skillsPath());
        this.bundled = path(config.bundledSkillsPath());
        this.project = path(project);
        this.vault = path(vault);
        this.capabilities = capabilities;
    }
    static Path path(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.startsWith("~")) value = System.getProperty("user.home") + value.substring(1);
        return Path.of(value).toAbsolutePath().normalize();
    }
    static Yaml parser() {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(MAX_CONTENT);
        return new Yaml(new SafeConstructor(options));
    }
    Result validate(String directory, String content, boolean references) {
        var diagnostics = new ArrayList<Diagnostic>();
        String name = "", description = "";
        Map<?, ?> fields = Map.of();
        String[] lines = content == null ? new String[0] : content.split("\\R", -1);
        int end = -1;
        if (content == null || content.length() > MAX_CONTENT || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTENT) {
            error(diagnostics, "size", "Skill content must be at most 256 KiB.", 1, 1);
        } else if (lines.length == 0 || !lines[0].equals("---")) {
            error(diagnostics, "frontmatter", "Start with a YAML frontmatter delimiter (---).", 1, 1);
        } else {
            for (int i = 1; i < lines.length; i++) if (lines[i].equals("---")) { end = i; break; }
            if (end < 0) error(diagnostics, "frontmatter", "Missing closing frontmatter delimiter (---).", lines.length, 1);
            else {
                try {
                    Object parsed = parser().load(String.join("\n", Arrays.copyOfRange(lines, 1, end)));
                    if (parsed instanceof Map<?, ?> map) fields = map;
                    else error(diagnostics, "yaml", "Frontmatter must contain key: value fields.", 2, 1);
                } catch (MarkedYAMLException e) {
                    var mark = e.getProblemMark();
                    error(diagnostics, "yaml", e.getProblem(), mark == null ? 2 : mark.getLine() + 2,
                            mark == null ? 1 : mark.getColumn() + 1);
                } catch (RuntimeException e) {
                    error(diagnostics, "yaml", "Invalid or excessively complex YAML.", 2, 1);
                }
                if (diagnostics.isEmpty()) {
                    name = required(fields, "name", lines, end, diagnostics);
                    description = required(fields, "description", lines, end, diagnostics);
                }
            }
        }
        String capabilityStatus = "unchecked";
        String vaultStatus = vault == null ? "disabled" : Files.isDirectory(vault) ? "available" : "unavailable";
        if (references && diagnostics.isEmpty()) {
            var snapshot = capabilities.current();
            capabilityStatus = snapshot.status();
            checkReferences(directory, lines, end, fields, snapshot, diagnostics);
        }
        return new Result(diagnostics.stream().noneMatch(d -> d.severity().equals("error")), name, description,
                List.copyOf(diagnostics.subList(0, Math.min(100, diagnostics.size()))), capabilityStatus, vaultStatus);
    }
    private static String required(Map<?, ?> fields, String key, String[] lines, int end, List<Diagnostic> out) {
        Object value = fields.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            error(out, "required-" + key, key + " must be a non-empty string.", fieldLine(key, lines, end), 1);
            return "";
        }
        return ((String) value).strip();
    }
    private static int fieldLine(String key, String[] lines, int end) {
        for (int i = 1; i < end; i++) if (lines[i].matches("^" + Pattern.quote(key) + "\\s*:.*")) return i + 1;
        return 2;
    }
    private static final Pattern TOOL = Pattern.compile("(?i)(?:\\b(?:call|invoke|use)\\s+(?:the\\s+)?`([A-Za-z][\\w-]*)`\\s+tool|\\btool\\s*:\\s*`?([A-Za-z][\\w-]*)`?)");
    private static final Pattern FILE = Pattern.compile("(?<![\\w/:])((?:examples|docs)/[A-Za-z0-9_./-]+\\.[A-Za-z0-9]+)");
    private static final Pattern WIKI = Pattern.compile("\\[\\[([^]\\r\\n]+)]]");
    private void checkReferences(String directory, String[] lines, int end, Map<?, ?> fields,
            SkillCapabilities.Snapshot snapshot, List<Diagnostic> out) {
        if (snapshot.status().equals("available")) {
            Object allowed = fields.get("allowed-tools");
            List<?> tools = allowed instanceof List<?> list ? list : allowed instanceof String text ? List.of(text.split("[,\\s]+")) : List.of();
            for (Object tool : tools) if (tool instanceof String name) checkTool(name, fieldLine("allowed-tools", lines, end), 1, snapshot, out);
        }
        int illustrativeLevel = 0;
        boolean fenced = false;
        Set<String> vaultIndex = null;
        for (int i = end + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")) fenced = !fenced;
            if (!fenced && line.matches("^#{1,6} .*")) {
                int level = line.indexOf(' ');
                if (illustrativeLevel > 0 && level <= illustrativeLevel) illustrativeLevel = 0;
                if (line.toLowerCase(Locale.ROOT).matches(".*(example|template|sample|generated content).*")) illustrativeLevel = level;
            }
            if (out.size() >= 100) return;
            Matcher tool = TOOL.matcher(line);
            while (tool.find()) {
                // Negative instructions ("NOT a tool") and CLI names are documentation, not invocations.
                if (snapshot.status().equals("available")) checkTool(tool.group(1) != null ? tool.group(1) : tool.group(2), i + 1, tool.start() + 1, snapshot, out);
            }
            Matcher file = FILE.matcher(line);
            while (file.find()) {
                String target = file.group(1);
                if (!exists(local == null ? null : local.resolve(directory), target)
                        && !exists(bundled == null ? null : bundled.resolve(directory), target)
                        && !exists(project, target)) {
                    warn(out, "missing-file", "File not found: " + target, i + 1, file.start() + 1);
                }
            }
            Matcher wiki = WIKI.matcher(line);
            while (wiki.find()) {
                String target = wiki.group(1).split("[|#]", 2)[0].strip();
                // Tutorial/template links describe notes the user may create, not installed dependencies.
                // Concrete links in ordinary code examples are still checked.
                if (illustrativeLevel > 0 || line.matches(".*obsidian\\s+(?:create|append)\\s+.*content=.*")
                        || Set.of("path", "target", "note", "note-path").contains(target)
                        || vault == null || !Files.isDirectory(vault) || target.isBlank()
                        || target.matches(".*[<>{}].*") || target.equals("wikilinks")
                        || target.contains("YYYY-MM-DD")) continue;
                if (vaultIndex == null) vaultIndex = wikiIndex();
                String note = target.endsWith(".md") ? target : target + ".md";
                if (!vaultIndex.contains(note)) warn(out, "missing-wikilink", "Vault note not found: " + target, i + 1, wiki.start() + 1);
                if (out.size() >= 100) return;
            }
        }
    }
    private Set<String> wikiIndex() {
        var notes = new HashSet<String>();
        try (var walk = Files.walk(vault, 12)) {
            walk.limit(10000).filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> exists(vault, vault.relativize(p).toString())).forEach(p -> {
                        notes.add(vault.relativize(p).toString());
                        notes.add(p.getFileName().toString());
                    });
        } catch (IOException ignored) { /* No claims that missing notes exist. */ }
        return notes;
    }
    static boolean exists(Path root, String target) {
        if (root == null) return false;
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path resolved = normalizedRoot.resolve(target).normalize();
            if (!resolved.startsWith(normalizedRoot)) return false;
            // Disallow symlink components, even links to another readable project directory.
            for (Path current = resolved; current != null && current.startsWith(normalizedRoot); current = current.getParent())
                if (Files.isSymbolicLink(current)) return false;
            return Files.isRegularFile(resolved) && resolved.toRealPath().startsWith(normalizedRoot.toRealPath());
        } catch (IOException | InvalidPathException e) { return false; }
    }
    private static void checkTool(String name, int line, int column, SkillCapabilities.Snapshot snapshot, List<Diagnostic> out) {
        if (!snapshot.tools().contains(name)) warn(out, "unavailable-tool", "Tool is not available in the running bot: " + name, line, column);
    }
    private static void error(List<Diagnostic> out, String code, String message, int line, int column) {
        out.add(new Diagnostic("error", code, message, Math.max(1, line), column));
    }
    private static void warn(List<Diagnostic> out, String code, String message, int line, int column) {
        out.add(new Diagnostic("warning", code, message, line, column));
    }
}
