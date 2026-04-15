# Issue #255 — AutoMemoryToolsAdvisor Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Herald's custom `MemoryMdAdvisor` with the library's `AutoMemoryToolsAdvisor`, consolidating memory tool registration and system-prompt injection into a single advisor.

**Architecture:** The library advisor implements `BaseChatMemoryAdvisor` and owns its own `AutoMemoryTools` internally. Migration removes the standalone tool registration, the `{long_term_memory_instructions}` placeholder substitution, and the `MemoryMdAdvisor` class itself. Memory wiring consolidates into one `AutoMemoryToolsAdvisor.builder()` call inside `buildAdvisorChain`.

**Tech Stack:** Java 21 · Spring Boot · spring-ai-agent-utils 0.7.0 · Maven multi-module

**Spec:** `docs/superpowers/specs/2026-04-10-issue-255-auto-memory-tools-advisor.md`

---

## File Structure

**Files to delete (1)**
- `herald-core/src/main/java/com/herald/agent/MemoryMdAdvisor.java`

**Files to modify (6)**
- `herald-core/src/main/resources/prompts/AUTO_MEMORY_SYSTEM_PROMPT.md` — drop `{MEMORIES_ROOT_DIRECTORY}` placeholder
- `herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md` — drop `{long_term_memory_instructions}` placeholder
- `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` — remove `AutoMemoryTools` wiring, `memoryPromptResource` param, `memoryInstructions` var, replace `MemoryMdAdvisor` with library advisor, update `buildToolList` / `resolvePrompt` signatures
- `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigTest.java` — drop `memoryPromptResource` arg from `modelSwitcher` call
- `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java` — drop `memoryPromptResource` arg from 4 `modelSwitcher` calls, update `buildToolList` test, update advisor-chain comment
- `herald-bot/src/test/java/com/herald/agent/HeraldA2aIntegrationTest.java` — drop `memoryPromptResource` arg from 1 `modelSwitcher` call

---

## Task 1: Update the two prompt resource files

These are plain markdown edits. They can land first because they don't affect compilation until Task 2 removes the placeholder substitution logic. Since the substitution is a simple `String.replace`, unsubstituted placeholders in the source leave literal `{...}` text in the resolved prompt if Task 2 doesn't land — which is the correct failure mode (visible and loud at runtime).

**Files:**
- Modify: `herald-core/src/main/resources/prompts/AUTO_MEMORY_SYSTEM_PROMPT.md`
- Modify: `herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md`

- [ ] **Step 1: Drop `{MEMORIES_ROOT_DIRECTORY}` from AUTO_MEMORY_SYSTEM_PROMPT.md**

Use the Edit tool on `herald-core/src/main/resources/prompts/AUTO_MEMORY_SYSTEM_PROMPT.md`:

- old_string:
```
You have a persistent, file-based memory system at {MEMORIES_ROOT_DIRECTORY}.
All paths passed to memory tools are relative to that root.
```
- new_string:
```
You have a persistent, file-based memory system. All paths passed to memory
tools are relative to the memory root directory.
```

- [ ] **Step 2: Drop `{long_term_memory_instructions}` from MAIN_AGENT_SYSTEM_PROMPT.md**

Use the Edit tool on `herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md`:

- old_string:
```
# Memory Management

{long_term_memory_instructions}

## Prior Context Lookup
```
- new_string:
```
# Memory Management

## Prior Context Lookup
```

- [ ] **Step 3: Verify no other references to the placeholders exist**

Use the Grep tool:
- Pattern: `MEMORIES_ROOT_DIRECTORY|long_term_memory_instructions`
- Glob: `**/src/main/resources/**/*.md`

Expected: no results.

If any other prompt file still references these placeholders, stop and report — the plan assumed only these two files use them.

- [ ] **Step 4: Commit**

```bash
git add herald-core/src/main/resources/prompts/AUTO_MEMORY_SYSTEM_PROMPT.md \
         herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md
git commit -m "docs: remove placeholder substitutions from memory prompts (#255)"
```

---

## Task 2: Rewire HeraldAgentConfig to use AutoMemoryToolsAdvisor

This is the core behavior change. It bundles all the code edits together — removing the separate `AutoMemoryTools` instantiation, the `memoryPromptResource` parameter, the `memoryInstructions` local variable, the `{long_term_memory_instructions}` replacement in `resolvePrompt`, the `autoMemoryTools` parameter from `buildToolList`, and swapping `MemoryMdAdvisor` for the library advisor inside `buildAdvisorChain`. They must land together or the build breaks.

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`

- [ ] **Step 1: Swap the imports**

Use the Edit tool on `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`.

- old_string:
```
import org.springaicommunity.agent.tools.AutoMemoryTools;
```
- new_string:
```
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
```

Note: `ClassPathResource`, `Ordered`, and `Resource` are already imported. `MemoryMdAdvisor` lives in the same package so no import line to remove for it.

- [ ] **Step 2: Remove the `memoryPromptResource` parameter from `modelSwitcher`**

Use the Edit tool:

- old_string:
```
            @Value("classpath:prompts/MAIN_AGENT_SYSTEM_PROMPT.md") Resource promptResource,
            @Value("classpath:prompts/AUTO_MEMORY_SYSTEM_PROMPT.md") Resource memoryPromptResource,
            @Value("${herald.agent.agents-directory:.claude/agents}") String agentsDirectory,
```
- new_string:
```
            @Value("classpath:prompts/MAIN_AGENT_SYSTEM_PROMPT.md") Resource promptResource,
            @Value("${herald.agent.agents-directory:.claude/agents}") String agentsDirectory,
```

- [ ] **Step 3: Remove the standalone `AutoMemoryTools` instantiation and `memoryInstructions` variable**

Use the Edit tool:

- old_string:
```
        AutoMemoryTools autoMemoryTools = AutoMemoryTools.builder()
                .memoriesDir(memoriesDir)
                .build();

        // Resolve system prompt with memory instructions
        String memoryInstructions = loadPromptTemplate(memoryPromptResource)
                .replace("{MEMORIES_ROOT_DIRECTORY}", memoriesDir.toString());
        String promptTemplate = loadPromptTemplate(promptResource);
        String systemPrompt = resolvePrompt(promptTemplate, config, defaultModel, memoryInstructions);
```
- new_string:
```
        // Resolve system prompt (memory instructions are injected by AutoMemoryToolsAdvisor)
        String promptTemplate = loadPromptTemplate(promptResource);
        String systemPrompt = resolvePrompt(promptTemplate, config, defaultModel);
```

- [ ] **Step 4: Update the `buildToolList` call site to drop `autoMemoryTools`**

Use the Edit tool:

- old_string:
```
        var toolList = buildToolList(autoMemoryTools, shellDecorator, fsTools,
                todoTool, askTool, telegramSendToolOpt, gwsToolsOpt, webTools, cronToolsOpt);
```
- new_string:
```
        var toolList = buildToolList(shellDecorator, fsTools,
                todoTool, askTool, telegramSendToolOpt, gwsToolsOpt, webTools, cronToolsOpt);
```

- [ ] **Step 5: Replace `MemoryMdAdvisor` with `AutoMemoryToolsAdvisor` inside `buildAdvisorChain`**

Use the Edit tool:

- old_string:
```
        // Long-term memory — injects MEMORY.md index each turn
        advisors.add(new MemoryMdAdvisor(memoriesDir));
```
- new_string:
```
        // Long-term memory — library advisor owns per-request memory tool registration
        // and injects the memory system prompt each turn. The no-op consolidation trigger
        // locks in current behavior against future library default changes.
        advisors.add(AutoMemoryToolsAdvisor.builder()
                .memoriesRootDirectory(memoriesDir.toString())
                .memorySystemPrompt(new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md"))
                .order(Ordered.HIGHEST_PRECEDENCE + 100)
                .memoryConsolidationTrigger((req, instant) -> false)
                .build());
```

- [ ] **Step 6: Update the `buildToolList` signature and body**

Use the Edit tool:

- old_string:
```
    List<Object> buildToolList(
            AutoMemoryTools autoMemoryTools,
            HeraldShellDecorator shellDecorator,
            FileSystemTools fsTools,
            org.springaicommunity.agent.tools.TodoWriteTool todoTool,
            AskUserQuestionTool askTool,
            Optional<TelegramSendTool> telegramSendToolOpt,
            Optional<GwsTools> gwsToolsOpt,
            WebTools webTools,
            Optional<CronTools> cronToolsOpt) {

        List<Object> tools = new ArrayList<>();
        tools.add(shellDecorator);
        tools.add(fsTools);
        tools.add(autoMemoryTools);
        tools.add(todoTool);
        tools.add(askTool);
        tools.add(webTools);
```
- new_string:
```
    List<Object> buildToolList(
            HeraldShellDecorator shellDecorator,
            FileSystemTools fsTools,
            org.springaicommunity.agent.tools.TodoWriteTool todoTool,
            AskUserQuestionTool askTool,
            Optional<TelegramSendTool> telegramSendToolOpt,
            Optional<GwsTools> gwsToolsOpt,
            WebTools webTools,
            Optional<CronTools> cronToolsOpt) {

        List<Object> tools = new ArrayList<>();
        tools.add(shellDecorator);
        tools.add(fsTools);
        tools.add(todoTool);
        tools.add(askTool);
        tools.add(webTools);
```

- [ ] **Step 7: Update the `resolvePrompt` signature and body**

Use the Edit tool:

- old_string:
```
    String resolvePrompt(String template, HeraldConfig config, String modelId, String memoryInstructions) {
        return template
                .replace("{persona}", config.persona())
                .replace("{model_id}", modelId)
                .replace("{long_term_memory_instructions}", memoryInstructions)
                .replace("{system_prompt_extra}", config.systemPromptExtra());
    }
```
- new_string:
```
    String resolvePrompt(String template, HeraldConfig config, String modelId) {
        return template
                .replace("{persona}", config.persona())
                .replace("{model_id}", modelId)
                .replace("{system_prompt_extra}", config.systemPromptExtra());
    }
```

- [ ] **Step 8: test-compile herald-bot to verify production code still compiles**

Run from repo root:
```bash
./mvnw -pl herald-bot -am test-compile -q
```

Expected: production compile succeeds but test compilation **will fail** on the existing test call sites that still pass the old argument shapes. That is the expected state — Task 3 fixes those tests.

If the production code fails to compile (anything in `src/main`), stop and report. Test failures at this step are expected and acceptable.

To isolate the production compile specifically:
```bash
./mvnw -pl herald-bot compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit the production change**

Do not run tests yet — they are known-broken until Task 3.

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git commit -m "refactor: use AutoMemoryToolsAdvisor from spring-ai-agent-utils (#255)"
```

---

## Task 3: Update test call sites

The 6 test call sites that pass `new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md")` to `modelSwitcher(...)` all need to drop that argument. One test directly calls `buildToolList` with the old argument list and needs adjusting. One comment references `MemoryMdAdvisor` and needs updating.

**Files:**
- Modify: `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigTest.java`
- Modify: `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java`
- Modify: `herald-bot/src/test/java/com/herald/agent/HeraldA2aIntegrationTest.java`

- [ ] **Step 1: Update `HeraldAgentConfigTest.java`**

Use the Edit tool on `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigTest.java`. The call site is inside `loadPromptTemplateThrowsForMissingResource`.

- old_string:
```
                        new ClassPathResource("prompts/NONEXISTENT.md"),
                        new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md"),
                        ".claude/agents", new ReloadableSkillsTool("skills"),
```
- new_string:
```
                        new ClassPathResource("prompts/NONEXISTENT.md"),
                        ".claude/agents", new ReloadableSkillsTool("skills"),
```

- [ ] **Step 2: Update `HeraldAgentConfigIntegrationTest.java` — first `modelSwitcher` call**

Use the Edit tool. The file has 4 `modelSwitcher` call sites, each preceded by slightly different context. For each, the call block contains the line:
```
                new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md"),
```
immediately after `new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),`. Remove the second `ClassPathResource` line from each of the 4 call sites.

Do 4 separate Edit tool calls. Use `replace_all: false` for each. To disambiguate the call sites, include 2-3 surrounding lines of context unique to each site.

Use the Read tool first to capture the exact surrounding context for all 4 sites. Then Edit each site using this pattern:

- old_string:
```
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
```
- new_string:
```
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
```

This pattern should match **only** if it's unique across the file. If it matches multiple times (it should, since it appears at all 4 sites), use `replace_all: true` on a single Edit tool call instead. Verify the resulting file has zero occurrences of `AUTO_MEMORY_SYSTEM_PROMPT.md` after the Edit.

- [ ] **Step 3: Update the advisor-chain comment in `HeraldAgentConfigIntegrationTest.java`**

Use the Edit tool:

- old_string:
```
        // Should have: DateTimePromptAdvisor, ContextMdAdvisor, MemoryMdAdvisor, PromptDumpAdvisor, ToolCallAdvisor
```
- new_string:
```
        // Should have: DateTimePromptAdvisor, ContextMdAdvisor, AutoMemoryToolsAdvisor, PromptDumpAdvisor, ToolCallAdvisor
```

- [ ] **Step 4: Update the `buildToolListContainsAutoMemoryToolsAndStatelessTools` test**

The existing test passes `autoMemoryTools` as the first arg and asserts the list has 6 items. After the signature change, both the arg and the count change.

Use the Edit tool on `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java`:

- old_string:
```
    @Test
    void buildToolListContainsAutoMemoryToolsAndStatelessTools() {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();
        var autoMemoryTools = AutoMemoryTools.builder()
                .memoriesDir(Path.of("/tmp/test-memories"))
                .build();
        var todoTool = org.springaicommunity.agent.tools.TodoWriteTool.builder().build();
        var askTool = org.springaicommunity.agent.tools.AskUserQuestionTool.builder()
                .questionHandler(q -> java.util.Map.of())
                .build();

        var tools = agentConfig.buildToolList(
                autoMemoryTools,
                mock(HeraldShellDecorator.class),
                new FileSystemTools(),
                todoTool, askTool,
                Optional.empty(), Optional.empty(),
                new WebTools(""),
                Optional.empty());

        // shellDecorator, fsTools, autoMemoryTools, todoTool, askTool, webTools = 6
        assertThat(tools).hasSize(6);
    }
}
```
- new_string:
```
    @Test
    void buildToolListContainsStatelessTools() {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();
        var todoTool = org.springaicommunity.agent.tools.TodoWriteTool.builder().build();
        var askTool = org.springaicommunity.agent.tools.AskUserQuestionTool.builder()
                .questionHandler(q -> java.util.Map.of())
                .build();

        var tools = agentConfig.buildToolList(
                mock(HeraldShellDecorator.class),
                new FileSystemTools(),
                todoTool, askTool,
                Optional.empty(), Optional.empty(),
                new WebTools(""),
                Optional.empty());

        // shellDecorator, fsTools, todoTool, askTool, webTools = 5
        // (AutoMemoryTools is now registered by AutoMemoryToolsAdvisor, not in the tool list)
        assertThat(tools).hasSize(5);
    }
}
```

- [ ] **Step 5: Remove the now-unused `AutoMemoryTools` import from `HeraldAgentConfigIntegrationTest.java`**

After Step 4 removes the last reference to `AutoMemoryTools` in this file, the import becomes unused.

Use the Grep tool to confirm no other references exist:
- Pattern: `AutoMemoryTools`
- Glob: `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java`

Expected: zero matches after Step 4.

If confirmed, use the Edit tool:

- old_string:
```
import org.springaicommunity.agent.tools.AutoMemoryTools;
```
- new_string: (empty — delete the line)

If the Grep still shows matches, stop and report — Step 4 didn't fully clean up.

- [ ] **Step 6: Update `HeraldA2aIntegrationTest.java`**

Use the Edit tool. The call site is inside `modelSwitcherRegistersA2aAgentFromConfig`.

- old_string:
```
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
```
- new_string:
```
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
```

- [ ] **Step 7: Run the full herald-bot test suite with a clean reactor**

IMPORTANT: use `clean` to avoid stale installed JAR issues.

```bash
./mvnw -pl herald-bot -am clean test 2>&1 | grep -E 'Tests run|BUILD' | tail -15
```

Expected: `Tests run: 23` (or 24 if a new test was added), `0 failures, 0 errors`, `BUILD SUCCESS`.

If any test fails because of `MemoryMdAdvisor` references that Step 3 or Step 4 missed, open the failing file and fix the reference manually using the Edit tool.

- [ ] **Step 8: Commit**

```bash
git add herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigTest.java \
         herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java \
         herald-bot/src/test/java/com/herald/agent/HeraldA2aIntegrationTest.java
git commit -m "test: update modelSwitcher call sites after AutoMemoryToolsAdvisor migration (#255)"
```

---

## Task 4: Delete MemoryMdAdvisor

With no remaining references in tracked code, the class can be deleted.

**Files:**
- Delete: `herald-core/src/main/java/com/herald/agent/MemoryMdAdvisor.java`

- [ ] **Step 1: Delete the file**

```bash
rm herald-core/src/main/java/com/herald/agent/MemoryMdAdvisor.java
```

- [ ] **Step 2: Verify no references remain in tracked code**

Use the Grep tool:
- Pattern: `MemoryMdAdvisor`
- Glob: `**/src/**/*.java`

Expected: zero results. Stale worktree copies under `.claude/worktrees/**` do not count.

If any reference remains, the Task 3 edits didn't fully clean up — stop and report.

- [ ] **Step 3: Clean reactor build across all 4 modules**

```bash
./mvnw -pl herald-bot,herald-core,herald-persistence,herald-telegram -am clean verify 2>&1 | tail -15
```

Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 4: Commit**

```bash
git add -A herald-core/src/main/java/com/herald/agent/MemoryMdAdvisor.java
git commit -m "refactor: delete MemoryMdAdvisor (#255)"
```

---

## Task 5: Final verification

- [ ] **Step 1: Full clean verify**

```bash
./mvnw -pl herald-bot,herald-core,herald-persistence,herald-telegram -am clean verify
```

Expected: BUILD SUCCESS, all 23+ herald-bot tests green, 54 herald-core tests green, 85 herald-telegram tests green.

- [ ] **Step 2: Grep for lingering symbols**

Use the Grep tool on each pattern:

1. `MemoryMdAdvisor`
2. `memoryInstructions`
3. `memoryPromptResource`
4. `long_term_memory_instructions`
5. `MEMORIES_ROOT_DIRECTORY`
6. `import org.springaicommunity.agent.tools.AutoMemoryTools;`

Expected hits for each:
- Pattern 1-5: zero hits in `**/src/**/*.java` and `**/src/**/*.md` (worktree copies under `.claude/worktrees/` don't count). The spec and plan files (`docs/superpowers/specs/2026-04-10-issue-255-*`, `docs/superpowers/plans/2026-04-10-issue-255-*`) are expected to reference them and should be ignored.
- Pattern 6: zero hits anywhere in tracked code (the import is no longer needed because `AutoMemoryTools` is only used internally by the advisor now).

Any unexpected hit means a file was missed — resolve before proceeding.

- [ ] **Step 3: Confirm issue acceptance criteria**

Re-read the "Alignment Plan" checklist in `gh issue view 255`:

1. ✅ Upgrade `spring-ai-agent-utils` to `0.7.0` — already done in commit `b340723`
2. ✅ Add `AutoMemoryToolsAdvisor` to the advisor chain at `HIGHEST_PRECEDENCE + 200` — Task 2 Step 5 (actually at `HIGHEST_PRECEDENCE + 100` to match the legacy `MemoryMdAdvisor` slot; the spec documents this intentional deviation)
3. ⚠️ "Keep SQLite MemoryTools as hot facts" — superseded by commit `659d515` which fully replaced SQLite memory with file-based
4. ✅ Expose `AutoMemoryTools` six tools — done in commit `659d515`; still in effect (the advisor registers them per-request instead of via `defaultTools`)
5. ✅ Update agent system prompt — done in commit `659d515`; AUTO_MEMORY_SYSTEM_PROMPT.md is now injected by the library advisor instead of baked into the main prompt
6. ✅ Migrate existing SQLite entries — `MemoryMigrationJob` ran and was subsequently removed in `659d515`

- [ ] **Step 4: No new commit needed**

This task is verification only.
