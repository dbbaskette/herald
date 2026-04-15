# AgentProfile Record and Parser — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create an `AgentProfile` record and YAML frontmatter parser for the `agents.md` single-file agent configuration format.

**Architecture:** A Java record `AgentProfile` captures parsed frontmatter fields. An `AgentProfileParser` uses SnakeYAML (already available via Spring Boot) to parse YAML frontmatter from markdown files, returning the record plus the system prompt body text. The parser reuses the frontmatter extraction pattern from `SkillsController.parseFrontmatter()`.

**Tech Stack:** Java 21 records, SnakeYAML 2.5 (Spring Boot transitive), JUnit 5, AssertJ

---

## File Structure

| File | Responsibility |
|------|---------------|
| Create: `herald-bot/src/main/java/com/herald/agent/profile/AgentProfile.java` | Record holding parsed agent configuration fields |
| Create: `herald-bot/src/main/java/com/herald/agent/profile/AgentProfileParser.java` | Parses YAML frontmatter from markdown into AgentProfile + system prompt |
| Create: `herald-bot/src/test/java/com/herald/agent/profile/AgentProfileParserTest.java` | Unit tests for parser |

---

### Task 1: Create AgentProfile Record with Tests

**Files:**
- Create: `herald-bot/src/test/java/com/herald/agent/profile/AgentProfileParserTest.java` (initial test for record)
- Create: `herald-bot/src/main/java/com/herald/agent/profile/AgentProfile.java`

- [ ] **Step 1: Write the failing test for AgentProfile record**

```java
package com.herald.agent.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProfileParserTest {

    @Test
    void agentProfileRecordHoldsAllFields() {
        var profile = new AgentProfile(
                "cf-analyzer",
                "Analyzes Cloud Foundry environments",
                "sonnet",
                "anthropic",
                List.of("filesystem", "shell", "web"),
                null,   // skillsDirectory
                null,   // subagentsDirectory
                false,  // memory
                "./CONTEXT.md",
                200_000
        );

        assertThat(profile.name()).isEqualTo("cf-analyzer");
        assertThat(profile.description()).isEqualTo("Analyzes Cloud Foundry environments");
        assertThat(profile.model()).isEqualTo("sonnet");
        assertThat(profile.provider()).isEqualTo("anthropic");
        assertThat(profile.tools()).containsExactly("filesystem", "shell", "web");
        assertThat(profile.skillsDirectory()).isNull();
        assertThat(profile.subagentsDirectory()).isNull();
        assertThat(profile.memory()).isFalse();
        assertThat(profile.contextFile()).isEqualTo("./CONTEXT.md");
        assertThat(profile.maxTokens()).isEqualTo(200_000);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd herald-bot && ../mvnw test -pl . -Dtest="AgentProfileParserTest" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20`
Expected: FAIL — `AgentProfile` class does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package com.herald.agent.profile;

import java.util.List;

/**
 * Parsed agent configuration from an {@code agents.md} YAML frontmatter block.
 * This is a superset of the {@code .claude/agents/*.md} subagent format, adding
 * fields for provider selection, memory, context file, and token limits.
 *
 * @param name              agent identifier
 * @param description       human-readable description
 * @param model             model selector (e.g. "sonnet", "opus", "gpt-4o")
 * @param provider          provider name (e.g. "anthropic", "openai", "ollama"); nullable
 * @param tools             list of tool names to enable
 * @param skillsDirectory   path to skills directory; nullable
 * @param subagentsDirectory path to subagents directory; nullable
 * @param memory            whether to enable persistent memory
 * @param contextFile       path to CONTEXT.md file; nullable
 * @param maxTokens         maximum context window tokens; nullable
 */
public record AgentProfile(
        String name,
        String description,
        String model,
        String provider,
        List<String> tools,
        String skillsDirectory,
        String subagentsDirectory,
        boolean memory,
        String contextFile,
        Integer maxTokens
) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd herald-bot && ../mvnw test -pl . -Dtest="AgentProfileParserTest" 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/profile/AgentProfile.java herald-bot/src/test/java/com/herald/agent/profile/AgentProfileParserTest.java
git commit -m "feat: add AgentProfile record for agent configuration (#215)"
```

---

### Task 2: Create AgentProfileParser with Tests

**Files:**
- Modify: `herald-bot/src/test/java/com/herald/agent/profile/AgentProfileParserTest.java` (add parser tests)
- Create: `herald-bot/src/main/java/com/herald/agent/profile/AgentProfileParser.java`

- [ ] **Step 1: Write failing tests for the parser**

Add these tests to `AgentProfileParserTest.java`:

```java
@Test
void parsesFullAgentMd() {
    String content = """
            ---
            name: cf-analyzer
            description: Analyzes Cloud Foundry environments
            model: sonnet
            provider: anthropic
            tools: [filesystem, shell, web, skills]
            skills_directory: ./skills
            subagents_directory: ./.claude/agents
            memory: false
            context_file: ./CONTEXT.md
            max_tokens: 200000
            ---

            You are a Cloud Foundry operations analyst.
            """;

    AgentProfileParser.Result result = AgentProfileParser.parse(content);

    assertThat(result.profile().name()).isEqualTo("cf-analyzer");
    assertThat(result.profile().description()).isEqualTo("Analyzes Cloud Foundry environments");
    assertThat(result.profile().model()).isEqualTo("sonnet");
    assertThat(result.profile().provider()).isEqualTo("anthropic");
    assertThat(result.profile().tools()).containsExactly("filesystem", "shell", "web", "skills");
    assertThat(result.profile().skillsDirectory()).isEqualTo("./skills");
    assertThat(result.profile().subagentsDirectory()).isEqualTo("./.claude/agents");
    assertThat(result.profile().memory()).isFalse();
    assertThat(result.profile().contextFile()).isEqualTo("./CONTEXT.md");
    assertThat(result.profile().maxTokens()).isEqualTo(200_000);
    assertThat(result.systemPrompt()).isEqualTo("You are a Cloud Foundry operations analyst.");
}

@Test
void parsesWithMissingOptionalFields() {
    String content = """
            ---
            name: simple-agent
            description: A simple agent
            ---

            Do the thing.
            """;

    AgentProfileParser.Result result = AgentProfileParser.parse(content);

    assertThat(result.profile().name()).isEqualTo("simple-agent");
    assertThat(result.profile().description()).isEqualTo("A simple agent");
    assertThat(result.profile().model()).isNull();
    assertThat(result.profile().provider()).isNull();
    assertThat(result.profile().tools()).isEmpty();
    assertThat(result.profile().skillsDirectory()).isNull();
    assertThat(result.profile().subagentsDirectory()).isNull();
    assertThat(result.profile().memory()).isFalse();
    assertThat(result.profile().contextFile()).isNull();
    assertThat(result.profile().maxTokens()).isNull();
    assertThat(result.systemPrompt()).isEqualTo("Do the thing.");
}

@Test
void parsesCommaSeparatedTools() {
    String content = """
            ---
            name: test
            description: test
            tools: Read, Grep, Glob
            ---

            prompt
            """;

    AgentProfileParser.Result result = AgentProfileParser.parse(content);

    assertThat(result.profile().tools()).containsExactly("Read", "Grep", "Glob");
}

@Test
void throwsForMissingFrontmatter() {
    String content = "Just a plain markdown file with no frontmatter.";

    assertThatThrownBy(() -> AgentProfileParser.parse(content))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("frontmatter");
}

@Test
void throwsForMissingName() {
    String content = """
            ---
            description: no name field
            ---

            prompt
            """;

    assertThatThrownBy(() -> AgentProfileParser.parse(content))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
}

@Test
void parsesFromFile() throws IOException {
    Path file = Files.createTempFile("agent", ".md");
    Files.writeString(file, """
            ---
            name: file-agent
            description: Loaded from file
            model: opus
            memory: true
            ---

            You are loaded from a file.
            """);

    AgentProfileParser.Result result = AgentProfileParser.parseFile(file);

    assertThat(result.profile().name()).isEqualTo("file-agent");
    assertThat(result.profile().memory()).isTrue();
    assertThat(result.systemPrompt()).isEqualTo("You are loaded from a file.");

    Files.deleteIfExists(file);
}

@Test
void memoryDefaultsToFalse() {
    String content = """
            ---
            name: test
            description: test
            ---

            prompt
            """;

    AgentProfileParser.Result result = AgentProfileParser.parse(content);
    assertThat(result.profile().memory()).isFalse();
}
```

Add these imports to the test file:
```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd herald-bot && ../mvnw test -pl . -Dtest="AgentProfileParserTest" 2>&1 | tail -20`
Expected: FAIL — `AgentProfileParser` class does not exist

- [ ] **Step 3: Write the parser implementation**

```java
package com.herald.agent.profile;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Parses an {@code agents.md} markdown file with YAML frontmatter into an
 * {@link AgentProfile} and system prompt text.
 *
 * <p>The format uses standard YAML frontmatter delimited by {@code ---} lines,
 * followed by a markdown body that becomes the system prompt.  This is a superset
 * of the {@code .claude/agents/*.md} subagent format used by spring-ai-agent-utils.
 */
public final class AgentProfileParser {

    private AgentProfileParser() {}

    /** Parsed result containing the profile and system prompt body. */
    public record Result(AgentProfile profile, String systemPrompt) {}

    /** Parse from a file path. */
    public static Result parseFile(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return parse(content);
    }

    /** Parse from string content. */
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

        Yaml yaml = new Yaml();
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
        // Handle comma-separated string: "Read, Grep, Glob"
        return List.of(value.toString().split(",")).stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd herald-bot && ../mvnw test -pl . -Dtest="AgentProfileParserTest" 2>&1 | tail -20`
Expected: PASS — all 8 tests green

- [ ] **Step 5: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/profile/AgentProfileParser.java herald-bot/src/test/java/com/herald/agent/profile/AgentProfileParserTest.java
git commit -m "feat: add AgentProfileParser for agents.md YAML frontmatter (#215)"
```
