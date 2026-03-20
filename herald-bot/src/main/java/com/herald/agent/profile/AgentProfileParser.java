package com.herald.agent.profile;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Parses an {@code agents.md} markdown file with YAML frontmatter into an
 * {@link AgentProfile} and system prompt text.
 */
public final class AgentProfileParser {

    private AgentProfileParser() {}

    public record Result(AgentProfile profile, String systemPrompt) {}

    public static Result parseFile(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return parse(content);
    }

    public static Result parse(String content) {
        if (content == null || !content.startsWith("---")) {
            throw new IllegalArgumentException("Content must start with YAML frontmatter (---)");
        }

        int firstDelim = content.indexOf("---");
        int secondDelim = content.indexOf("---", firstDelim + 3);
        if (secondDelim < 0) {
            throw new IllegalArgumentException("Missing closing frontmatter delimiter (---)");
        }

        String yamlBlock = content.substring(firstDelim + 3, secondDelim).trim();
        String body = content.substring(secondDelim + 3).trim();

        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> frontmatter = yaml.load(yamlBlock);
        if (frontmatter == null) {
            throw new IllegalArgumentException("Empty frontmatter");
        }

        String name = stringField(frontmatter, "name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent profile requires a 'name' field in frontmatter");
        }

        AgentProfile profile = new AgentProfile(
                name,
                stringField(frontmatter, "description"),
                stringField(frontmatter, "model"),
                stringField(frontmatter, "provider"),
                toolsList(frontmatter),
                stringField(frontmatter, "skills_directory"),
                stringField(frontmatter, "subagents_directory"),
                booleanField(frontmatter, "memory", false),
                stringField(frontmatter, "context_file"),
                integerField(frontmatter, "max_tokens")
        );

        return new Result(profile, body);
    }

    private static String stringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString().strip() : null;
    }

    private static boolean booleanField(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString().strip());
        return defaultValue;
    }

    private static Integer integerField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try { return Integer.parseInt(value.toString().strip()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toolsList(Map<String, Object> map) {
        Object value = map.get("tools");
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).map(String::strip).toList();
        }
        return List.of(value.toString().split(",")).stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
