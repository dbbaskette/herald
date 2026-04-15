# Issue #256 — ToolSearchTool Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the static `ToolCallAdvisor` with `ToolSearchToolCallAdvisor` so the model can dynamically discover tools via semantic search at inference time.

**Architecture:** `ToolSearchToolCallAdvisor` is a drop-in replacement for `ToolCallAdvisor`. It wraps the same tool-call loop but adds a `toolSearchTool` that the model can invoke to find tools by description. Under the hood, a `LuceneToolSearcher` indexes all registered tools at the start of each conversation and returns the top matches.

**Tech Stack:** `tool-search-tool` 2.0.1, `tool-searcher-lucene` 2.0.1 (from `org.springaicommunity`)

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `pom.xml` (parent) | Modify | Add version property for tool-search-tool |
| `herald-bot/pom.xml` | Modify | Add `tool-searcher-lucene` dependency |
| `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` | Modify | Replace `ToolCallAdvisor` with `ToolSearchToolCallAdvisor` |
| `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java` | Modify | Update advisor chain assertions |

---

### Task 1: Add Maven dependencies

**Files:**
- Modify: `pom.xml:72-98` (parent dependencyManagement)
- Modify: `herald-bot/pom.xml:52-57` (dependencies)

- [ ] **Step 1: Add version property and managed dependency to parent POM**

In `pom.xml`, add to `<dependencyManagement><dependencies>`:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>tool-searcher-lucene</artifactId>
    <version>2.0.1</version>
</dependency>
```

- [ ] **Step 2: Add dependency to herald-bot POM**

In `herald-bot/pom.xml`, add after the `spring-ai-agent-utils-a2a` dependency:

```xml
<!-- Tool search / dynamic tool discovery (Blog 7) -->
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>tool-searcher-lucene</artifactId>
</dependency>
```

- [ ] **Step 3: Verify dependency resolution**

Run: `./mvnw dependency:resolve -pl herald-bot -q`
Expected: exits 0, no errors

- [ ] **Step 4: Commit**

```bash
git add pom.xml herald-bot/pom.xml
git commit -m "build: add tool-searcher-lucene dependency (#256)"
```

---

### Task 2: Replace ToolCallAdvisor with ToolSearchToolCallAdvisor

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java:28-29,356-362`

- [ ] **Step 1: Write the failing test**

In `HeraldAgentConfigIntegrationTest.java`, add this test:

```java
@Test
void advisorChainUsesToolSearchToolCallAdvisor(@TempDir Path tempDir) {
    HeraldAgentConfig agentConfig = new HeraldAgentConfig();
    ContextMdAdvisor contextMdAdvisor = new ContextMdAdvisor(Path.of("/tmp/test-context.md"));
    ChatModel mockModel = mock(ChatModel.class);
    HeraldConfig config = new HeraldConfig(null, null,
            new HeraldConfig.Agent("TestBot", null, null, null, null), null, null, null, null, null, null, null);

    var advisors = agentConfig.buildAdvisorChain(
            Optional.empty(), contextMdAdvisor, tempDir,
            mockModel, config, false);

    assertThat(advisors)
            .filteredOn(a -> a instanceof org.springaicommunity.tool.search.ToolSearchToolCallAdvisor)
            .hasSize(1);
    // ToolSearchToolCallAdvisor replaces ToolCallAdvisor — none of the base type should remain
    assertThat(advisors)
            .filteredOn(a -> a.getClass().equals(org.springframework.ai.chat.client.advisor.ToolCallAdvisor.class))
            .isEmpty();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl herald-bot -Dtest="HeraldAgentConfigIntegrationTest#advisorChainUsesToolSearchToolCallAdvisor" -q`
Expected: FAIL — `ToolSearchToolCallAdvisor` not found in advisor chain (currently `ToolCallAdvisor`)

- [ ] **Step 3: Replace ToolCallAdvisor with ToolSearchToolCallAdvisor in buildAdvisorChain**

In `HeraldAgentConfig.java`:

Replace the import:
```java
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
```
with:
```java
import org.springaicommunity.tool.search.ToolSearchToolCallAdvisor;
import org.springaicommunity.tool.searcher.LuceneToolSearcher;
```

Replace the `ToolCallAdvisor` block (lines ~360-362):
```java
advisors.add(ToolCallAdvisor.builder()
        .advisorOrder(Ordered.LOWEST_PRECEDENCE - 1)
        .build());
```
with:
```java
advisors.add(ToolSearchToolCallAdvisor.builder()
        .toolSearcher(new LuceneToolSearcher())
        .advisorOrder(Ordered.LOWEST_PRECEDENCE - 1)
        .build());
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl herald-bot -Dtest="HeraldAgentConfigIntegrationTest#advisorChainUsesToolSearchToolCallAdvisor" -q`
Expected: PASS

- [ ] **Step 5: Update existing advisor chain test**

In `buildAdvisorChainExcludesPersistenceAdvisorsWhenNoBeans`, the comment says
"PromptDumpAdvisor, ToolCallAdvisor" — `ToolSearchToolCallAdvisor` extends
`ToolCallAdvisor` so `instanceof` checks will still match. The count (5)
remains the same. Verify the existing test still passes:

Run: `./mvnw test -pl herald-bot -Dtest="HeraldAgentConfigIntegrationTest#buildAdvisorChainExcludesPersistenceAdvisorsWhenNoBeans" -q`
Expected: PASS (no changes needed — `ToolSearchToolCallAdvisor` is-a `ToolCallAdvisor`)

- [ ] **Step 6: Run full test suite**

Run: `./mvnw test -q`
Expected: all tests pass

- [ ] **Step 7: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java \
       herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java
git commit -m "feat: replace ToolCallAdvisor with ToolSearchToolCallAdvisor (#256)"
```

---

### Task 3: Add toolSearchTool to activeToolNames

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java:92-105`
- Modify: `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java`

The `activeToolNames` bean controls which tool names are passed to
`buildToolAliases` to avoid collisions. `toolSearchTool` is registered
automatically by the advisor, so it needs to be in this list to prevent
a skill-router alias from shadowing it.

- [ ] **Step 1: Add toolSearchTool to the activeToolNames list**

In `HeraldAgentConfig.java`, in the `activeToolNames` bean, add `"toolSearchTool"` to the initial list:

```java
List<String> names = new ArrayList<>(List.of(
        "shell", "filesystem", "todoWrite", "askUserQuestion",
        "task", "taskOutput", "skills", "web", "toolSearchTool",
        "MemoryView", "MemoryCreate", "MemoryStrReplace",
        "MemoryInsert", "MemoryDelete", "MemoryRename"));
```

- [ ] **Step 2: Update test call sites to include toolSearchTool in activeToolNames**

In all `modelSwitcher()` calls in `HeraldAgentConfigIntegrationTest.java`, update the last argument from:

```java
List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web")
```

to:

```java
List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web", "toolSearchTool")
```

There are 4 call sites in the test file (in `modelSwitcherBeanCreatedWithAllToolsAndAdvisors`, `modelSwitcherLoadsSubagentDefinitionsFromDirectory`, `modelSwitcherWiresOpenAiAndOllamaProviders`, and `modelSwitcherBuildsSuccessfullyWithNoPersistenceBeans`).

- [ ] **Step 3: Run full test suite**

Run: `./mvnw test -q`
Expected: all tests pass

- [ ] **Step 4: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java \
       herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java
git commit -m "feat: add toolSearchTool to activeToolNames whitelist (#256)"
```

---

### Task 4: Update documentation

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` (comment only)

- [ ] **Step 1: Update the advisor chain comment**

In `buildAdvisorChain`, update the comment above the `ToolSearchToolCallAdvisor` block:

```java
// ToolSearchToolCallAdvisor replaces ToolCallAdvisor — indexes all registered
// tools via LuceneToolSearcher and exposes a toolSearchTool for on-demand
// discovery. Explicit order just before ChatModelCallAdvisor (LOWEST_PRECEDENCE).
advisors.add(ToolSearchToolCallAdvisor.builder()
```

- [ ] **Step 2: Run tests one final time**

Run: `./mvnw test -q`
Expected: all tests pass

- [ ] **Step 3: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git commit -m "docs: document ToolSearchToolCallAdvisor in advisor chain comment (#256)"
```
