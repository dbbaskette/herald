# Issue #254 — A2A Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register remote A2A subagents declared under `herald.a2a.agents` in `herald.yaml` as additional `SubagentReference` entries at startup, alongside Herald's existing local in-process subagents.

**Architecture:** Add the `spring-ai-agent-utils-a2a` dependency, extend `HeraldConfig` with a new top-level `A2a` record (plus a backwards-compat constructor to avoid breaking 26 existing test call sites), wire an `A2ASubagentResolver` + `A2ASubagentExecutor` into `HeraldAgentConfig.modelSwitcher` when the config list is non-empty, and verify with a unit test plus a WireMock-backed integration test.

**Tech Stack:** Java 21 · Spring Boot · spring-ai-agent-utils 0.7.0 · spring-ai-agent-utils-a2a 0.7.0 · a2a-java-sdk-client 0.3.3.Final · WireMock 3.9.1 · Maven multi-module

**Spec:** `docs/superpowers/specs/2026-04-10-issue-254-a2a-integration.md`

---

## File Structure

**Files to modify (3)**
- `pom.xml` (parent) — add `spring-ai-agent-utils-a2a` to `<dependencyManagement>`
- `herald-bot/pom.xml` — add the compile dep (inherited version) + WireMock test dep
- `herald-core/src/main/java/com/herald/config/HeraldConfig.java` — add `A2a` and `A2aAgent` records, extend canonical constructor, add backwards-compat constructor, add `a2aAgents()` helper
- `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` — rewire `modelSwitcher` to register A2A types/refs when configured
- `README.md` — add a short "A2A agents" subsection

**Files to create (2)**
- `herald-bot/src/test/java/com/herald/config/HeraldConfigA2aTest.java` — unit test for the new accessor
- `herald-bot/src/test/java/com/herald/agent/HeraldA2aIntegrationTest.java` — WireMock-backed Spring integration test

**Files NOT to touch**
- The 26 existing `new HeraldConfig(...)` positional-call sites across the codebase. The backwards-compat constructor in `HeraldConfig` means zero call-site churn.

---

## Task 1: Add dependencies

**Files:**
- Modify: `pom.xml` (parent)
- Modify: `herald-bot/pom.xml`

- [ ] **Step 1: Add spring-ai-agent-utils-a2a to parent dependencyManagement**

Use the Edit tool on `pom.xml` (the parent POM, at the repo root).

- old_string:
```
            <dependency>
                <groupId>org.springaicommunity</groupId>
                <artifactId>spring-ai-agent-utils</artifactId>
                <version>0.7.0</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
```
- new_string:
```
            <dependency>
                <groupId>org.springaicommunity</groupId>
                <artifactId>spring-ai-agent-utils</artifactId>
                <version>0.7.0</version>
            </dependency>
            <dependency>
                <groupId>org.springaicommunity</groupId>
                <artifactId>spring-ai-agent-utils-a2a</artifactId>
                <version>0.7.0</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
```

- [ ] **Step 2: Add the a2a dep and WireMock test dep to herald-bot/pom.xml**

Use the Edit tool on `herald-bot/pom.xml`:

- old_string:
```
        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
```
- new_string:
```
        <!-- A2A remote subagent support -->
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-agent-utils-a2a</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>3.9.1</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

- [ ] **Step 3: Verify Maven resolves the new artifacts**

Run:
```bash
./mvnw -pl herald-bot dependency:tree -Dincludes=org.springaicommunity:spring-ai-agent-utils-a2a,org.wiremock:wiremock-standalone,io.github.a2asdk -q 2>&1 | tail -20
```

Expected: output includes `spring-ai-agent-utils-a2a:jar:0.7.0`, `a2a-java-sdk-client:jar:0.3.3.Final`, `a2a-java-sdk-client-transport-jsonrpc:jar:0.3.3.Final`, and `wiremock-standalone:jar:3.9.1`. No resolution errors.

- [ ] **Step 4: Compile herald-bot to confirm nothing clashes yet**

Run:
```bash
./mvnw -pl herald-bot -am test-compile -q
```

Expected: BUILD SUCCESS, exit 0.

If you see Jackson / Spring Boot / jakarta conflicts, stop and report with the exact error — the controller will guide `<exclusions>`.

- [ ] **Step 5: Commit**

```bash
git add pom.xml herald-bot/pom.xml
git commit -m "build: add spring-ai-agent-utils-a2a and WireMock test dep (#254)"
```

---

## Task 2: Extend HeraldConfig with A2a records

This task adds two new records, extends the canonical constructor with one field, adds a backwards-compat constructor so existing 10-arg call sites keep compiling, adds an accessor, and writes a unit test for the accessor.

**Files:**
- Modify: `herald-core/src/main/java/com/herald/config/HeraldConfig.java`
- Create: `herald-bot/src/test/java/com/herald/config/HeraldConfigA2aTest.java`

- [ ] **Step 1: Update HeraldConfig record signature and add A2a records**

Use the Edit tool on `herald-core/src/main/java/com/herald/config/HeraldConfig.java`.

- old_string:
```
package com.herald.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "herald")
public record HeraldConfig(Memory memory, Telegram telegram, Agent agent, Providers providers, Cron cron,
                           Weather weather, Obsidian obsidian, Vault vault, Archival archival,
                           LongTermMemory longTermMemory) {

    public record Memory(String dbPath) {
    }
```
- new_string:
```
package com.herald.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "herald")
public record HeraldConfig(Memory memory, Telegram telegram, Agent agent, Providers providers, Cron cron,
                           Weather weather, Obsidian obsidian, Vault vault, Archival archival,
                           LongTermMemory longTermMemory, A2a a2a) {

    /**
     * Backwards-compatible constructor for callers that predate the A2A addition.
     * Delegates to the canonical constructor with a null a2a value so existing
     * test fixtures and wiring code keep working unchanged.
     */
    public HeraldConfig(Memory memory, Telegram telegram, Agent agent, Providers providers, Cron cron,
                        Weather weather, Obsidian obsidian, Vault vault, Archival archival,
                        LongTermMemory longTermMemory) {
        this(memory, telegram, agent, providers, cron, weather, obsidian, vault, archival, longTermMemory, null);
    }

    public record A2a(List<A2aAgent> agents) {
    }

    public record A2aAgent(String name, String url, Map<String, String> metadata) {
    }

    public record Memory(String dbPath) {
    }
```

- [ ] **Step 2: Add the a2aAgents() accessor near the bottom of the file**

Use the Edit tool on the same file. Add the accessor just before the closing `}` of the `HeraldConfig` record, next to the other small accessors.

- old_string:
```
    public String dbPath() {
        if (memory != null && memory.dbPath() != null) {
            return memory.dbPath();
        }
        return "~/.herald/herald.db";
    }
}
```
- new_string:
```
    public String dbPath() {
        if (memory != null && memory.dbPath() != null) {
            return memory.dbPath();
        }
        return "~/.herald/herald.db";
    }

    /**
     * Returns the configured A2A agents, or an empty list if none are configured.
     */
    public java.util.List<A2aAgent> a2aAgents() {
        if (a2a != null && a2a.agents() != null) {
            return a2a.agents();
        }
        return java.util.List.of();
    }
}
```

Note: `java.util.List` is already imported in Step 1, but the fully-qualified form above is also safe. Use either; this step shows the FQ form to be unambiguous if `List` has another conflicting import.

- [ ] **Step 3: Compile herald-core to confirm the record change is clean**

Run:
```bash
./mvnw -pl herald-core -am compile -q
```

Expected: BUILD SUCCESS.

If the compile fails with a recursive-constructor-invocation error in the backwards-compat constructor, the `this(...)` call must list fields in the exact order of the canonical constructor. Re-read Step 1 and confirm ordering matches.

- [ ] **Step 4: Run the full test reactor across affected modules**

This is where the backwards-compat constructor proves itself. All 26 existing `new HeraldConfig(...)` call sites must keep compiling.

Run:
```bash
./mvnw -pl herald-bot,herald-core,herald-persistence,herald-telegram -am test-compile -q
```

Expected: BUILD SUCCESS.

If any test file fails to compile with "constructor HeraldConfig in class HeraldConfig cannot be applied", the backwards-compat constructor is missing or has a wrong signature — re-check Step 1.

- [ ] **Step 5: Create the unit test for a2aAgents()**

Create `herald-bot/src/test/java/com/herald/config/HeraldConfigA2aTest.java`:

```java
package com.herald.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldConfigA2aTest {

    @Test
    void a2aAgentsReturnsConfiguredListWhenPresent() {
        List<HeraldConfig.A2aAgent> agents = List.of(
                new HeraldConfig.A2aAgent("airbnb", "http://localhost:10001/airbnb",
                        Map.of("authorization", "Bearer token")),
                new HeraldConfig.A2aAgent("weather", "http://localhost:10002/weather", null));
        HeraldConfig config = configWithA2a(new HeraldConfig.A2a(agents));

        assertThat(config.a2aAgents()).hasSize(2);
        assertThat(config.a2aAgents().get(0).name()).isEqualTo("airbnb");
        assertThat(config.a2aAgents().get(0).url()).isEqualTo("http://localhost:10001/airbnb");
        assertThat(config.a2aAgents().get(0).metadata()).containsEntry("authorization", "Bearer token");
        assertThat(config.a2aAgents().get(1).metadata()).isNull();
    }

    @Test
    void a2aAgentsReturnsEmptyListWhenA2aIsNull() {
        HeraldConfig config = configWithA2a(null);
        assertThat(config.a2aAgents()).isEmpty();
    }

    @Test
    void a2aAgentsReturnsEmptyListWhenAgentsListIsNull() {
        HeraldConfig config = configWithA2a(new HeraldConfig.A2a(null));
        assertThat(config.a2aAgents()).isEmpty();
    }

    @Test
    void backwardsCompatibleConstructorLeavesA2aNull() {
        // 10-arg constructor path used by existing call sites
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);
        assertThat(config.a2a()).isNull();
        assertThat(config.a2aAgents()).isEmpty();
    }

    private HeraldConfig configWithA2a(HeraldConfig.A2a a2a) {
        // 11-arg canonical constructor
        return new HeraldConfig(null, null, null, null, null, null, null, null, null, null, a2a);
    }
}
```

- [ ] **Step 6: Run the new unit test**

Run:
```bash
./mvnw -pl herald-bot test -Dtest=HeraldConfigA2aTest -q
```

Expected: BUILD SUCCESS, 4 tests passed.

- [ ] **Step 7: Commit**

```bash
git add herald-core/src/main/java/com/herald/config/HeraldConfig.java \
         herald-bot/src/test/java/com/herald/config/HeraldConfigA2aTest.java
git commit -m "feat: add A2a/A2aAgent config records with backwards-compat constructor (#254)"
```

---

## Task 3: Rewire HeraldAgentConfig.modelSwitcher

Register an `A2ASubagentType` and convert each configured `A2aAgent` into a `SubagentReference` when the config list is non-empty.

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`

- [ ] **Step 1: Add A2A imports**

Use the Edit tool on `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`.

`SubagentReference`, `ClaudeSubagentType`, `ArrayList`, `List`, and `Map` are already imported. Only four new imports are needed.

- old_string:
```
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
```
- new_string:
```
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentDefinition;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentExecutor;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentResolver;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
```

- [ ] **Step 2: Rewrite the taskToolBuilder block**

Use the Edit tool:

- old_string:
```
        var subagentRefs = loadSubagentReferences(agentsDirectory);

        var taskToolBuilder = TaskTool.builder()
                .subagentTypes(subagentType)
                .taskRepository(taskRepository);

        if (!subagentRefs.isEmpty()) {
            taskToolBuilder.subagentReferences(subagentRefs);
        }
```
- new_string:
```
        var subagentRefs = loadSubagentReferences(agentsDirectory);
        var a2aAgents = config.a2aAgents();

        var taskToolBuilder = TaskTool.builder().taskRepository(taskRepository);

        if (a2aAgents.isEmpty()) {
            taskToolBuilder.subagentTypes(subagentType);
            if (!subagentRefs.isEmpty()) {
                taskToolBuilder.subagentReferences(subagentRefs);
            }
        } else {
            var a2aType = new SubagentType(new A2ASubagentResolver(), new A2ASubagentExecutor());
            taskToolBuilder.subagentTypes(subagentType, a2aType);

            var combinedRefs = new ArrayList<>(subagentRefs);
            for (var agent : a2aAgents) {
                Map<String, String> metadata = agent.metadata() != null ? agent.metadata() : Map.of();
                combinedRefs.add(new SubagentReference(agent.url(), A2ASubagentDefinition.KIND, metadata));
            }
            taskToolBuilder.subagentReferences(combinedRefs);
            log.info("Registered {} A2A agent(s) alongside {} local subagent(s)",
                    a2aAgents.size(), subagentRefs.size());
        }
```

- [ ] **Step 3: test-compile to verify the rewrite**

Run:
```bash
./mvnw -pl herald-bot -am test-compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Run the existing HeraldAgentConfigIntegrationTest to confirm no regression**

The existing tests pass `null` for every `HeraldConfig` field (via the backwards-compat constructor), so `config.a2aAgents()` returns an empty list and the `if` branch is taken — behavior is identical to before.

Run:
```bash
./mvnw -pl herald-bot test -Dtest=HeraldAgentConfigIntegrationTest -q
```

Expected: BUILD SUCCESS, all 4 existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git commit -m "feat: register A2A subagents from HeraldConfig.a2aAgents (#254)"
```

---

## Task 4: WireMock integration test

Create an integration test that spins up a WireMock server serving a minimal `agent-card.json`, configures a single A2A agent pointing at it, and verifies `modelSwitcher` successfully builds the bean graph.

**Files:**
- Create: `herald-bot/src/test/java/com/herald/agent/HeraldA2aIntegrationTest.java`

- [ ] **Step 1: Write the integration test**

Create `herald-bot/src/test/java/com/herald/agent/HeraldA2aIntegrationTest.java`:

```java
package com.herald.agent;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.herald.config.HeraldConfig;
import com.herald.cron.CronTools;
import com.herald.tools.FileSystemTools;
import com.herald.tools.GwsTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.TelegramSendTool;
import com.herald.tools.WebTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.AutoMemoryTools;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that when herald.a2a.agents is configured, modelSwitcher builds a
 * TaskTool with an A2A SubagentType registered. Does NOT attempt a real JSON-RPC
 * delegation — the WireMock server exists only to serve the agent-card.json
 * so that any eager resolver call would succeed, and to hold a stable URL
 * that can be recorded in the config.
 */
class HeraldA2aIntegrationTest {

    private static final String HAIKU_MODEL = "claude-haiku-4-5";
    private static final String SONNET_MODEL = "claude-sonnet-4-5";
    private static final String OPUS_MODEL = "claude-opus-4-5";
    private static final String OPENAI_MODEL = "gpt-4o";
    private static final String OLLAMA_MODEL = "llama3.2";
    private static final String GEMINI_MODEL = "gemini-2.5-flash";
    private static final String LMSTUDIO_MODEL = "qwen/qwen3.5-35b-a3b";

    private static final String AGENT_CARD_JSON = """
            {
              "name": "Test Agent",
              "description": "Integration test agent",
              "url": "%s/testagent",
              "version": "1.0.0",
              "capabilities": {},
              "skills": [],
              "defaultInputModes": ["text"],
              "defaultOutputModes": ["text"]
            }
            """;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void modelSwitcherRegistersA2aAgentFromConfig(@TempDir Path tempDir) {
        String baseUrl = "http://localhost:" + wireMock.getPort();
        String agentCard = String.format(AGENT_CARD_JSON, baseUrl);

        wireMock.stubFor(get(urlEqualTo("/testagent/.well-known/agent-card.json"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(agentCard)));

        HeraldAgentConfig agentConfig = new HeraldAgentConfig();
        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);
        ChatModel mockModel = mock(ChatModel.class);

        HeraldConfig.A2aAgent a2aAgent = new HeraldConfig.A2aAgent(
                "test-agent", baseUrl + "/testagent", Map.of());
        HeraldConfig config = new HeraldConfig(
                null, null,
                new HeraldConfig.Agent("TestBot", null, null, null, null),
                null, null, null, null, null, null, null,
                new HeraldConfig.A2a(List.of(a2aAgent)));

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockModel, config, false, Optional.of(chatMemory),
                mock(HeraldShellDecorator.class),
                new FileSystemTools(), Optional.empty(), mock(ObjectProvider.class),
                Optional.of(mock(TelegramSendTool.class)),
                Optional.of(mock(GwsTools.class)), new WebTools(""), Optional.of(mock(CronTools.class)),
                Optional.of(jdbcTemplate),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, GEMINI_MODEL, LMSTUDIO_MODEL,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web"));

        assertThat(switcher).isNotNull();
        assertThat(switcher.getActiveClient()).isNotNull();
    }
}
```

Key points:
- The WireMock stub is registered but the test does NOT assert the stub was called. `A2ASubagentResolver` is lazy — it only hits the well-known path when `TaskTool` dispatches a delegation. The stub exists as belt-and-suspenders in case the library becomes eager in a future version.
- The test reuses the exact fixture pattern from `HeraldAgentConfigIntegrationTest`, so if that file changes, this test's fixture may need the same tweak.
- The agent card JSON is minimal. If `A2ASubagentResolver` is invoked during the test run and Jackson rejects the body, the test will fail with a clear deserialization error — at that point, iterate on the fixture to add whatever fields the SDK requires.

- [ ] **Step 2: Run the integration test**

Run:
```bash
./mvnw -pl herald-bot test -Dtest=HeraldA2aIntegrationTest -q
```

Expected: BUILD SUCCESS, 1 test passed.

If the test fails with a WireMock port bind error, another test may be using port 8080 by default — WireMockConfiguration's `dynamicPort()` avoids this.

If the test fails with a Jackson deserialization error on the agent card, iterate on the `AGENT_CARD_JSON` fixture in the test file. Add whatever fields the A2A SDK requires until the resolver accepts it.

- [ ] **Step 3: Run the full herald-bot test suite to confirm no regression**

Run:
```bash
./mvnw -pl herald-bot test -q
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add herald-bot/src/test/java/com/herald/agent/HeraldA2aIntegrationTest.java
git commit -m "test: add WireMock integration test for A2A subagent wiring (#254)"
```

---

## Task 5: README documentation

Add a short "A2A agents" subsection to the README so users know how to configure remote A2A agents.

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Locate the right section**

Use the Grep tool with pattern `TodoWrite|Subagent` on `README.md` to find the area near the patterns table that lists blog 4 (subagent orchestration). Add the new subsection just below that table, as a standalone H3 heading.

- [ ] **Step 2: Append the A2A configuration subsection**

Use the Edit tool on `README.md`. Use the existing pattern row for TodoWrite as an anchor to find a unique insertion point.

- old_string:
```
| **A2A Protocol** | [Part 5: Agent2Agent Interoperability](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration/) | ⏳ | Planned for cross-agent communication. |
```
- new_string:
```
| **A2A Protocol** | [Part 5: Agent2Agent Interoperability](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration/) | ✅ | Configure remote A2A agents under `herald.a2a.agents` in `herald.yaml`; each entry is registered as a `SubagentReference` alongside local subagents and dispatched via the same `TaskTool`. Resolution is lazy — the agent card is fetched on first delegation. See the A2A agents section below for config shape. |
```

- [ ] **Step 3: Insert the full A2A subsection before the Contributing section**

Use the Edit tool on `README.md`. The anchor is the `## Contributing` heading near the end of the file — this is unique.

- old_string:
```
## Contributing

Contributions are welcome! To get started:
```
- new_string:
```
## A2A agents (remote subagents)

Herald can delegate to remote A2A-compliant agents alongside its local subagents. Declare each remote agent under `herald.a2a.agents` in `herald.yaml`:

```yaml
herald:
  a2a:
    agents:
      - name: airbnb-agent
        url: http://localhost:10001/airbnb
        metadata:
          authorization: "Bearer some-token"
      - name: weather-agent
        url: http://localhost:10002/weather
```

- `name` is a local label used in startup logs. The real display name comes from the resolved `AgentCard`.
- `metadata` is an optional map passed verbatim to the underlying `SubagentReference`.
- Resolution is **lazy**: Herald does not fetch `/.well-known/agent-card.json` at startup. A misconfigured or unreachable URL surfaces only on the first delegation to that agent.

## Contributing

Contributions are welcome! To get started:
```

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: document herald.a2a.agents configuration (#254)"
```

---

## Task 6: Final verification

- [ ] **Step 1: Clean build across affected modules**

Run:
```bash
./mvnw -pl herald-bot,herald-core,herald-persistence,herald-telegram -am verify
```

Expected: BUILD SUCCESS, all tests green. This is the authoritative signal that nothing is broken.

- [ ] **Step 2: Confirm the new A2A imports are used only where expected**

Use the Grep tool:
- Pattern: `A2ASubagent|a2aAgents|A2aAgent`
- Glob: `**/src/**/*.java`

Expected hits:
- `herald-core/src/main/java/com/herald/config/HeraldConfig.java` — records + accessor
- `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` — imports + wiring block
- `herald-bot/src/test/java/com/herald/config/HeraldConfigA2aTest.java` — unit test
- `herald-bot/src/test/java/com/herald/agent/HeraldA2aIntegrationTest.java` — integration test

No other files should contain these identifiers. Stale worktree copies under `.claude/worktrees/**` do not count.

- [ ] **Step 3: Confirm issue #254 acceptance criteria**

Re-read the issue's Alignment Plan:

1. ✅ Add `spring-ai-agent-utils-a2a` dependency — Task 1
2. ✅ Add `herald.a2a.agents` config surface — Task 2 (moved from `herald.agent.a2a-agents` per spec decision)
3. ✅ Register A2A `SubagentReference` entries + `SubagentType` in `HeraldAgentConfig` when configured — Task 3

- [ ] **Step 4: No new commit needed**

This task is verification only.
