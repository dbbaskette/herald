# Replace ClaudeSubagentType with Generic Herald Wrappers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hide Claude-specific naming behind Herald-branded factory/utility classes so the subagent wiring reads as model-agnostic.

**Architecture:** Thin wrapper approach — `HeraldSubagentFactory` creates a `SubagentType` record internally delegating to `ClaudeSubagentType.builder()`, and `HeraldSubagentReferences` delegates to `ClaudeSubagentReferences`. This avoids reimplementing the complex executor/resolver logic while presenting a clean API. If upstream adds a `GenericSubagentType` later, only the internals of these two classes change.

**Tech Stack:** Java 21, Spring AI, spring-ai-agent-utils 0.5.0

---

## File Structure

| File | Responsibility |
|------|---------------|
| Create: `herald-bot/src/main/java/com/herald/agent/subagent/HeraldSubagentFactory.java` | Builder/factory that produces a `SubagentType` record from named ChatClient.Builder entries — hides Claude naming |
| Create: `herald-bot/src/main/java/com/herald/agent/subagent/HeraldSubagentReferences.java` | Loads `SubagentReference` list from `.md` files in a directory — wraps `ClaudeSubagentReferences` |
| Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java:17-18,154-167,301-307` | Replace `ClaudeSubagentType` / `ClaudeSubagentReferences` imports and usage with Herald wrappers |
| Create: `herald-bot/src/test/java/com/herald/agent/subagent/HeraldSubagentFactoryTest.java` | Unit tests for the factory |
| Create: `herald-bot/src/test/java/com/herald/agent/subagent/HeraldSubagentReferencesTest.java` | Unit tests for the reference loader |
| Modify: `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java:160-196` | Update `loadSubagentReferences*` tests to verify through new wrappers |

---

### Task 1: Create HeraldSubagentFactory with Tests

**Files:**
- Create: `herald-bot/src/test/java/com/herald/agent/subagent/HeraldSubagentFactoryTest.java`
- Create: `herald-bot/src/main/java/com/herald/agent/subagent/HeraldSubagentFactory.java`

- [ ] **Step 1: Write the failing test**

```java
package com.herald.agent.subagent;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HeraldSubagentFactoryTest {

    @Test
    void buildReturnsSubagentTypeWithRegisteredModels() {
        ChatModel mockModel = mock(ChatModel.class);

        SubagentType result = HeraldSubagentFactory.builder()
                .chatClientBuilder("default", ChatClient.builder(mockModel))
                .chatClientBuilder("fast", ChatClient.builder(mockModel))
                .build();

        assertThat(result).isNotNull();
        assertThat(result.resolver()).isNotNull();
        assertThat(result.executor()).isNotNull();
    }

    @Test
    void buildWithSingleModelSucceeds() {
        ChatModel mockModel = mock(ChatModel.class);

        SubagentType result = HeraldSubagentFactory.builder()
                .chatClientBuilder("default", ChatClient.builder(mockModel))
                .build();

        assertThat(result).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd herald-bot && ../mvnw test -pl . -Dtest="HeraldSubagentFactoryTest" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20`
Expected: FAIL — `HeraldSubagentFactory` class does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package com.herald.agent.subagent;

import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Model-agnostic factory for building a {@link SubagentType} with named
 * ChatClient tiers.  Internally delegates to the upstream builder while
 * keeping Herald's public API free of provider-specific class names.
 */
public final class HeraldSubagentFactory {

    private HeraldSubagentFactory() {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final ClaudeSubagentType.Builder delegate = ClaudeSubagentType.builder();

        Builder() {}

        public Builder chatClientBuilder(String name, ChatClient.Builder clientBuilder) {
            delegate.chatClientBuilder(name, clientBuilder);
            return this;
        }

        public SubagentType build() {
            return delegate.build();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd herald-bot && ../mvnw test -pl . -Dtest="HeraldSubagentFactoryTest" 2>&1 | tail -20`
Expected: PASS — both tests green

- [ ] **Step 5: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/subagent/HeraldSubagentFactory.java herald-bot/src/test/java/com/herald/agent/subagent/HeraldSubagentFactoryTest.java
git commit -m "feat: add HeraldSubagentFactory wrapping ClaudeSubagentType (#213)"
```

---

### Task 2: Create HeraldSubagentReferences with Tests

**Files:**
- Create: `herald-bot/src/test/java/com/herald/agent/subagent/HeraldSubagentReferencesTest.java`
- Create: `herald-bot/src/main/java/com/herald/agent/subagent/HeraldSubagentReferences.java`

- [ ] **Step 1: Write the failing test**

```java
package com.herald.agent.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldSubagentReferencesTest {

    @Test
    void loadsReferencesFromDirectory(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("test-agent.md"),
                """
                ---
                name: test
                description: A test agent
                model: default
                tools: Read, Grep
                ---
                You are a test agent.
                """);

        List<SubagentReference> refs = HeraldSubagentReferences.fromDirectory(tempDir.toString());

        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst().uri()).contains("test-agent");
    }

    @Test
    void returnsEmptyForMissingDirectory() {
        List<SubagentReference> refs = HeraldSubagentReferences.fromDirectory("/nonexistent/path");
        assertThat(refs).isEmpty();
    }

    @Test
    void returnsEmptyForEmptyDirectory(@TempDir Path tempDir) {
        List<SubagentReference> refs = HeraldSubagentReferences.fromDirectory(tempDir.toString());
        assertThat(refs).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd herald-bot && ../mvnw test -pl . -Dtest="HeraldSubagentReferencesTest" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20`
Expected: FAIL — `HeraldSubagentReferences` class does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package com.herald.agent.subagent;

import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads {@link SubagentReference} definitions from markdown files in a
 * directory.  Delegates to the upstream loader while keeping Herald's
 * public API provider-neutral.
 */
public final class HeraldSubagentReferences {

    private HeraldSubagentReferences() {}

    public static List<SubagentReference> fromDirectory(String directory) {
        Path path = Path.of(directory);
        if (Files.isDirectory(path)) {
            return ClaudeSubagentReferences.fromRootDirectory(directory);
        }
        return List.of();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd herald-bot && ../mvnw test -pl . -Dtest="HeraldSubagentReferencesTest" 2>&1 | tail -20`
Expected: PASS — all three tests green

- [ ] **Step 5: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/subagent/HeraldSubagentReferences.java herald-bot/src/test/java/com/herald/agent/subagent/HeraldSubagentReferencesTest.java
git commit -m "feat: add HeraldSubagentReferences wrapping ClaudeSubagentReferences (#213)"
```

---

### Task 3: Swap HeraldAgentConfig to Use Herald Wrappers

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java:17-18,154-167,301-307`
- Modify: `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java:160-196`

- [ ] **Step 1: Update imports in HeraldAgentConfig.java**

Replace lines 17-18:
```java
// BEFORE:
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;

// AFTER:
import com.herald.agent.subagent.HeraldSubagentFactory;
import com.herald.agent.subagent.HeraldSubagentReferences;
```

- [ ] **Step 2: Replace ClaudeSubagentType.builder() usage (lines 154-167)**

```java
// BEFORE:
var subagentTypeBuilder = ClaudeSubagentType.builder()
        .chatClientBuilder("default", ChatClient.builder(chatModel))
        .chatClientBuilder("haiku", chatClientBuilderForModel(chatModel, haikuModel))
        .chatClientBuilder("sonnet", chatClientBuilderForModel(chatModel, sonnetModel))
        .chatClientBuilder("opus", chatClientBuilderForModel(chatModel, opusModel));

openaiChatModel.ifPresent(model ->
        subagentTypeBuilder.chatClientBuilder("openai", chatClientBuilderForModel(model, openaiModel)));
ollamaChatModel.ifPresent(model ->
        subagentTypeBuilder.chatClientBuilder("ollama", chatClientBuilderForModel(model, ollamaModel)));
geminiChatModel.ifPresent(model ->
        subagentTypeBuilder.chatClientBuilder("gemini", chatClientBuilderForModel(model, geminiModel)));

var claudeSubagentType = subagentTypeBuilder.build();

// AFTER:
var subagentTypeBuilder = HeraldSubagentFactory.builder()
        .chatClientBuilder("default", ChatClient.builder(chatModel))
        .chatClientBuilder("haiku", chatClientBuilderForModel(chatModel, haikuModel))
        .chatClientBuilder("sonnet", chatClientBuilderForModel(chatModel, sonnetModel))
        .chatClientBuilder("opus", chatClientBuilderForModel(chatModel, opusModel));

openaiChatModel.ifPresent(model ->
        subagentTypeBuilder.chatClientBuilder("openai", chatClientBuilderForModel(model, openaiModel)));
ollamaChatModel.ifPresent(model ->
        subagentTypeBuilder.chatClientBuilder("ollama", chatClientBuilderForModel(model, ollamaModel)));
geminiChatModel.ifPresent(model ->
        subagentTypeBuilder.chatClientBuilder("gemini", chatClientBuilderForModel(model, geminiModel)));

var subagentType = subagentTypeBuilder.build();
```

Also update the `TaskTool.builder()` call to use `subagentType` instead of `claudeSubagentType`.

- [ ] **Step 3: Replace loadSubagentReferences method (lines 301-307)**

```java
// BEFORE:
List<SubagentReference> loadSubagentReferences(String agentsDirectory) {
    Path agentsPath = Path.of(agentsDirectory);
    if (Files.isDirectory(agentsPath)) {
        return ClaudeSubagentReferences.fromRootDirectory(agentsDirectory);
    }
    return List.of();
}

// AFTER:
List<SubagentReference> loadSubagentReferences(String agentsDirectory) {
    return HeraldSubagentReferences.fromDirectory(agentsDirectory);
}
```

- [ ] **Step 4: Run all tests to verify nothing broke**

Run: `cd herald-bot && ../mvnw test -pl . 2>&1 | tail -30`
Expected: All tests PASS — behavior unchanged, only class names differ

- [ ] **Step 5: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git commit -m "refactor: use HeraldSubagentFactory/References instead of Claude-specific classes (#213)"
```

---

### Task 4: Remove Unused Imports and Verify Clean Build

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` (remove stale `java.nio.file.Files` / `Path` if now unused after `loadSubagentReferences` simplification)

- [ ] **Step 1: Check for unused imports**

Run: `cd herald-bot && ../mvnw compile -pl . 2>&1 | grep -i "warning\|unused" | head -20`

- [ ] **Step 2: Remove any unused imports if flagged**

Verify `java.nio.file.Files` and `java.nio.file.Path` are still used elsewhere in the file (they likely are for `resolveTildePath`). If not, remove them.

- [ ] **Step 3: Run full test suite**

Run: `cd herald-bot && ../mvnw test -pl . 2>&1 | tail -30`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 4: Commit if any cleanup was needed**

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git commit -m "chore: clean up unused imports after subagent refactor (#213)"
```
