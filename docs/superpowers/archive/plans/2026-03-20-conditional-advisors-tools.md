# Conditional Advisors and Tools — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gate persistence-dependent advisors and tools so they only activate when their backing beans exist, enabling ephemeral (no-DB) mode.

**Architecture:** Two-layer approach:
1. Add `@ConditionalOnBean` to `@Component`-annotated tool classes so Spring skips them when dependencies are absent.
2. Make persistence-dependent parameters `Optional` in `modelSwitcher()` and dynamically assemble advisor/tool lists.

Key insight: `ToolPairSanitizingAdvisor` is not currently wired (superseded by `JsonChatMemoryRepository`) — skip it. `HeraldShellDecorator` already handles optional JdbcTemplate gracefully — keep it always active. `TelegramSendTool` already uses `ObjectProvider<TelegramSender>` — just add `@ConditionalOnBean(TelegramSender.class)` so the bean itself is absent in no-Telegram mode.

**Tech Stack:** Spring Boot conditional annotations, Java Optional, existing test patterns

---

## File Structure

| File | Responsibility |
|------|---------------|
| Modify: `herald-bot/src/main/java/com/herald/memory/MemoryTools.java` | Add @ConditionalOnBean(MemoryRepository.class) |
| Modify: `herald-bot/src/main/java/com/herald/cron/CronTools.java` | Add @ConditionalOnBean(CronService.class) |
| Modify: `herald-bot/src/main/java/com/herald/tools/TelegramSendTool.java` | Add @ConditionalOnBean(TelegramSender.class) |
| Modify: `herald-bot/src/main/java/com/herald/tools/GwsTools.java` | Add @ConditionalOnProperty for GWS client ID |
| Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` | Make persistence params Optional, build advisor/tool lists dynamically |
| Modify: `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java` | Add ephemeral-mode test, update existing tests |
| Modify: `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigTest.java` | Update if affected |

---

### Task 1: Add @ConditionalOnBean to Persistence-Dependent Tool Components

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/memory/MemoryTools.java`
- Modify: `herald-bot/src/main/java/com/herald/cron/CronTools.java`
- Modify: `herald-bot/src/main/java/com/herald/tools/TelegramSendTool.java`
- Modify: `herald-bot/src/main/java/com/herald/tools/GwsTools.java`

- [ ] **Step 1: Add @ConditionalOnBean to MemoryTools**

In `MemoryTools.java`, add the annotation:
```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

@Component
@ConditionalOnBean(MemoryRepository.class)
public class MemoryTools {
```

- [ ] **Step 2: Add @ConditionalOnBean to CronTools**

In `CronTools.java`:
```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.herald.cron.CronService;

@Component
@ConditionalOnBean(CronService.class)
public class CronTools {
```

- [ ] **Step 3: Add @ConditionalOnBean to TelegramSendTool**

In `TelegramSendTool.java`:
```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.herald.telegram.TelegramSender;

@Component
@ConditionalOnBean(TelegramSender.class)
public class TelegramSendTool {
```

- [ ] **Step 4: Add @ConditionalOnProperty to GwsTools**

In `GwsTools.java`, gate on JdbcTemplate since it requires database access for Google credentials:
```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;

@Component
@ConditionalOnBean(JdbcTemplate.class)
public class GwsTools {
```

- [ ] **Step 5: Verify compilation**

Run: `cd herald-bot && ../mvnw compile -pl . 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add herald-bot/src/main/java/com/herald/memory/MemoryTools.java herald-bot/src/main/java/com/herald/cron/CronTools.java herald-bot/src/main/java/com/herald/tools/TelegramSendTool.java herald-bot/src/main/java/com/herald/tools/GwsTools.java
git commit -m "feat: gate persistence tools with @ConditionalOnBean (#217)"
```

---

### Task 2: Make Persistence Parameters Optional in modelSwitcher()

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`

This task changes the `modelSwitcher()` signature to accept persistence-dependent beans as `Optional` and dynamically assembles the advisor chain and tool list.

- [ ] **Step 1: Change persistence parameters to Optional**

In the `modelSwitcher()` method signature, change these parameters:
```java
// BEFORE:
ChatMemory chatMemory,
MemoryTools memoryTools,
...
TelegramSendTool telegramSendTool,
GwsTools gwsTools,
...
CronTools cronTools,
JdbcTemplate jdbcTemplate,

// AFTER:
Optional<ChatMemory> chatMemoryOpt,
Optional<MemoryTools> memoryToolsOpt,
...
Optional<TelegramSendTool> telegramSendToolOpt,
Optional<GwsTools> gwsToolsOpt,
...
Optional<CronTools> cronToolsOpt,
Optional<JdbcTemplate> jdbcTemplateOpt,
```

Keep non-persistence params as-is: `ChatModel chatModel`, `HeraldConfig config`, `HeraldShellDecorator shellDecorator`, `FileSystemTools fsTools`, `WebTools webTools`, `ApplicationEventPublisher eventPublisher`, `ObjectProvider<TelegramQuestionHandler> questionHandlerProvider`, etc.

- [ ] **Step 2: Extract advisor chain builder method**

Create a new method in HeraldAgentConfig:
```java
List<Advisor> buildAdvisorChain(
        Optional<MemoryTools> memoryToolsOpt,
        Optional<ChatMemory> chatMemoryOpt,
        ContextMdAdvisor contextMdAdvisor,
        Optional<ChatModel> chatModelOpt,
        HeraldConfig config,
        boolean promptDump,
        ToolSearcher toolSearcher) {

    List<Advisor> advisors = new ArrayList<>();

    // Always active
    advisors.add(new DateTimePromptAdvisor(DEFAULT_TIMEZONE, DATETIME_FORMAT));
    advisors.add(contextMdAdvisor);

    // Persistence-dependent
    memoryToolsOpt.ifPresent(mt ->
            advisors.add(new MemoryBlockAdvisor(mt)));

    if (chatMemoryOpt.isPresent()) {
        ChatMemory chatMemory = chatMemoryOpt.get();
        if (memoryToolsOpt.isPresent() && chatModelOpt.isPresent()) {
            advisors.add(new ContextCompactionAdvisor(
                    chatMemory, memoryToolsOpt.get(), chatModelOpt.get(),
                    config.maxContextTokens()));
        }
        advisors.add(new OneShotMemoryAdvisor(chatMemory, MAX_CONVERSATION_MESSAGES));
    }

    // Conditional on property (not persistence)
    advisors.add(new PromptDumpAdvisor(promptDump));

    // Always active
    advisors.add(ToolSearchToolCallAdvisor.builder()
            .toolSearcher(toolSearcher)
            .advisorOrder(Ordered.LOWEST_PRECEDENCE - 1)
            .build());

    return advisors;
}
```

- [ ] **Step 3: Extract tool list builder method**

Create a new method:
```java
List<Object> buildToolList(
        Optional<MemoryTools> memoryToolsOpt,
        HeraldShellDecorator shellDecorator,
        FileSystemTools fsTools,
        org.springaicommunity.agent.tools.TodoWriteTool todoTool,
        AskUserQuestionTool askTool,
        Optional<TelegramSendTool> telegramSendToolOpt,
        Optional<GwsTools> gwsToolsOpt,
        WebTools webTools,
        Optional<CronTools> cronToolsOpt) {

    List<Object> tools = new ArrayList<>();

    // Always active
    tools.add(shellDecorator);
    tools.add(fsTools);
    tools.add(todoTool);
    tools.add(askTool);
    tools.add(webTools);

    // Persistence-dependent
    memoryToolsOpt.ifPresent(tools::add);
    telegramSendToolOpt.ifPresent(tools::add);
    gwsToolsOpt.ifPresent(tools::add);
    cronToolsOpt.ifPresent(tools::add);

    return tools;
}
```

- [ ] **Step 4: Update the clientBuilderFactory to use dynamic lists**

Replace the hardcoded `.defaultTools(...)` and `.defaultAdvisors(...)` calls with the extracted methods:
```java
var advisorChain = buildAdvisorChain(memoryToolsOpt, chatMemoryOpt,
        contextMdAdvisor, Optional.of(chatModel), config, promptDump, toolSearcher);

var toolList = buildToolList(memoryToolsOpt, shellDecorator, fsTools,
        todoTool, askTool, telegramSendToolOpt, gwsToolsOpt, webTools, cronToolsOpt);

Function<ChatModel, ChatClient.Builder> clientBuilderFactory = cm ->
        ChatClient.builder(cm)
                .defaultSystem(systemPrompt)
                .defaultTools(toolList.toArray())
                .defaultToolCallbacks(taskTool, taskOutputTool, reloadableSkillsTool)
                .defaultAdvisors(advisorChain.toArray(new Advisor[0]));
```

- [ ] **Step 5: Update all references to unwrap Optionals**

Update inline code that uses `chatMemory`, `memoryTools`, `jdbcTemplate`, etc. to unwrap from Optional. Key spots:
- `chatMemory()` @Bean method — this already receives `JdbcChatMemoryRepository` which itself is conditional. The `chatMemory` bean should also be conditional.
- `ContextCompactionAdvisor` creation uses `chatMemory` and `memoryTools` — now handled in `buildAdvisorChain`.
- `ContextMdAdvisor` — no change (core).
- `ModelSwitcher` constructor takes `jdbcTemplate` — pass `jdbcTemplateOpt.orElse(null)`. Also add null guards in `ModelSwitcher.loadPersistedOverride()` and `switchModel()` around `jdbcTemplate.query()`/`jdbcTemplate.update()` calls — wrap them in `if (jdbcTemplate != null)` checks so ModelSwitcher works without persistence.

- [ ] **Step 6: Verify compilation**

Run: `cd herald-bot && ../mvnw compile -pl . 2>&1 | tail -10`

- [ ] **Step 7: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git commit -m "refactor: make persistence params Optional and build advisor/tool lists dynamically (#216, #217)"
```

---

### Task 3: Add Integration Tests for Ephemeral Mode

**Files:**
- Modify: `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java`

- [ ] **Step 1: Write ephemeral-mode test (no persistence beans)**

Add a test that passes `Optional.empty()` for all persistence parameters:
```java
@Test
void modelSwitcherBuildsSuccessfullyWithNoPersistenceBeans(@TempDir Path tempDir) {
    HeraldAgentConfig agentConfig = new HeraldAgentConfig();

    ChatModel mockModel = mock(ChatModel.class);

    HeraldConfig config = new HeraldConfig(null, null,
            new HeraldConfig.Agent("TestBot", null, null, null, null), null, null, null);

    ModelSwitcher switcher = agentConfig.modelSwitcher(
            mockModel, config, false,
            Optional.empty(),  // chatMemory
            Optional.empty(),  // memoryTools
            mock(HeraldShellDecorator.class),
            new FileSystemTools(),
            mock(ApplicationEventPublisher.class),
            mock(ObjectProvider.class),
            Optional.empty(),  // telegramSendTool
            Optional.empty(),  // gwsTools
            new WebTools(""),
            Optional.empty(),  // cronTools
            Optional.empty(),  // jdbcTemplate
            new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
            tempDir.toString(),
            new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
            new LuceneToolSearcher(0.4f),
            "claude-sonnet-4-5", "claude-haiku-4-5", "claude-sonnet-4-5", "claude-opus-4-5",
            "gpt-4o", "llama3.2", "gemini-2.5-flash",
            Optional.empty(), Optional.empty(), Optional.empty());

    assertThat(switcher).isNotNull();
    assertThat(switcher.getActiveClient()).isNotNull();
}
```

- [ ] **Step 2: Write test to verify advisor list in ephemeral mode**

Add a test for `buildAdvisorChain()`:
```java
@Test
void buildAdvisorChainExcludesPersistenceAdvisorsWhenNoBeans() {
    HeraldAgentConfig agentConfig = new HeraldAgentConfig();

    Path contextPath = Path.of("/tmp/test-context.md");
    ContextMdAdvisor contextMdAdvisor = new ContextMdAdvisor(contextPath);

    List<Advisor> advisors = agentConfig.buildAdvisorChain(
            Optional.empty(),  // no memoryTools
            Optional.empty(),  // no chatMemory
            contextMdAdvisor,
            Optional.empty(),  // no chatModel for compaction
            new HeraldConfig(null, null,
                    new HeraldConfig.Agent("TestBot", null, null, null, null), null, null, null),
            false,
            new LuceneToolSearcher(0.4f));

    // Should contain only: DateTimePromptAdvisor, ContextMdAdvisor, PromptDumpAdvisor, ToolSearchToolCallAdvisor
    assertThat(advisors).hasSize(4);
    assertThat(advisors).noneMatch(a -> a instanceof MemoryBlockAdvisor);
    assertThat(advisors).noneMatch(a -> a instanceof OneShotMemoryAdvisor);
    assertThat(advisors).noneMatch(a -> a instanceof ContextCompactionAdvisor);
}
```

- [ ] **Step 3: Write test to verify tool list in ephemeral mode**

```java
@Test
void buildToolListContainsOnlyStatelessToolsWhenNoPersistenceBeans() {
    HeraldAgentConfig agentConfig = new HeraldAgentConfig();

    var todoTool = org.springaicommunity.agent.tools.TodoWriteTool.builder().build();
    var askTool = AskUserQuestionTool.builder()
            .questionHandler(q -> Map.of())
            .build();

    List<Object> tools = agentConfig.buildToolList(
            Optional.empty(),  // no memoryTools
            mock(HeraldShellDecorator.class),
            new FileSystemTools(),
            todoTool,
            askTool,
            Optional.empty(),  // no telegramSendTool
            Optional.empty(),  // no gwsTools
            new WebTools(""),
            Optional.empty()); // no cronTools

    // Should contain only: shellDecorator, fsTools, todoTool, askTool, webTools
    assertThat(tools).hasSize(5);
}
```

- [ ] **Step 4: Update existing integration tests for new Optional signatures**

Update the 3 existing tests in `HeraldAgentConfigIntegrationTest` to wrap persistence parameters in `Optional.of(...)`:
- `modelSwitcherBeanCreatedWithAllToolsAndAdvisors` — wrap `chatMemory`, `memoryTools`, `telegramSendTool`, `gwsTools`, `cronTools`, `jdbcTemplate` in `Optional.of()`
- `modelSwitcherLoadsSubagentDefinitionsFromDirectory` — same
- `modelSwitcherWiresOpenAiAndOllamaProviders` — same

- [ ] **Step 5: Run all tests**

Run: `cd herald-bot && ../mvnw test -pl . 2>&1 | tail -30`
Expected: All tests pass including new ephemeral-mode tests

- [ ] **Step 6: Commit**

```bash
git add herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java
git commit -m "test: add ephemeral-mode integration tests for conditional advisors and tools (#216, #217)"
```

---

### Task 4: Update activeToolNames Bean for Dynamic Mode

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`

- [ ] **Step 1: Make activeToolNames dynamic**

The `activeToolNames()` bean at line 85 is a hardcoded list. Update it to reflect which tools are actually present:

```java
@Bean
@Qualifier("activeToolNames")
public List<String> activeToolNames(
        Optional<MemoryTools> memoryTools,
        Optional<CronTools> cronTools,
        Optional<TelegramSendTool> telegramSendTool,
        Optional<GwsTools> gwsTools) {
    List<String> names = new ArrayList<>(List.of(
            "shell", "filesystem", "todoWrite", "askUserQuestion",
            "task", "taskOutput", "skills", "web"));
    memoryTools.ifPresent(t -> names.add("memory"));
    cronTools.ifPresent(t -> names.add("cron"));
    telegramSendTool.ifPresent(t -> names.add("telegram_send"));
    gwsTools.ifPresent(t -> names.add("gws"));
    return List.copyOf(names);
}
```

- [ ] **Step 2: Run tests**

Run: `cd herald-bot && ../mvnw test -pl . 2>&1 | tail -20`

- [ ] **Step 3: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git commit -m "feat: make activeToolNames dynamic based on available beans (#217)"
```

---

### Task 5: Make ChatMemory Bean Conditional

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` (the `chatMemory()` @Bean method)
- Modify: `herald-bot/src/main/java/com/herald/config/DataSourceConfig.java` (add @ConditionalOnProperty for DataSource)

- [ ] **Step 1: Gate chatMemory @Bean on ChatMemoryRepository presence**

The existing `chatMemory()` method:
```java
@Bean
public ChatMemory chatMemory(JdbcChatMemoryRepository chatMemoryRepository) { ... }
```

Add conditional (note: actual signature takes `ChatMemoryRepository` interface, not `JdbcChatMemoryRepository`):
```java
@Bean
@ConditionalOnBean(ChatMemoryRepository.class)
public ChatMemory chatMemory(ChatMemoryRepository repository) { ... }
```

- [ ] **Step 2: Verify DataSourceConfig is already conditional or gate it**

Check if DataSourceConfig creates its beans unconditionally. If so, gate the DataSource and JdbcTemplate beans on a property like `herald.persistence.enabled` (defaulting to true for backward compatibility) or on the presence of a database file path.

Read DataSourceConfig first to understand the current setup before making changes.

- [ ] **Step 3: Run full test suite**

Run: `cd herald-bot && ../mvnw test -pl . 2>&1 | tail -30`

- [ ] **Step 4: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java herald-bot/src/main/java/com/herald/config/DataSourceConfig.java
git commit -m "feat: gate ChatMemory and DataSource beans for ephemeral mode (#216)"
```
