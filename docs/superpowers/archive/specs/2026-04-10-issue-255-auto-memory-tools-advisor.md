# Issue #255 — Migrate MemoryMdAdvisor → AutoMemoryToolsAdvisor

**Issue:** [dbbaskette/herald#255](https://github.com/dbbaskette/herald/issues/255)
**Status:** Design approved 2026-04-10

## Background

Herald migrated its memory system from SQLite to `AutoMemoryTools` in commit
`659d515` (`feat: replace memory system with AutoMemoryTools (Blog Part 6)`,
April 9, 2026). That commit:

- Upgraded `spring-ai-agent-utils` 0.5.0 → 0.7.0
- Wired the library's `AutoMemoryTools` (6 sandboxed ops: `MemoryView`,
  `MemoryCreate`, `MemoryStrReplace`, `MemoryInsert`, `MemoryDelete`,
  `MemoryRename`) into the main `ChatClient`
- Added `AUTO_MEMORY_SYSTEM_PROMPT.md` describing the typed memory taxonomy
  (`user`, `feedback`, `project`, `reference`)
- Deleted the legacy SQLite `MemoryTools`, `MemoryRepository`,
  `MemoryBlockAdvisor`, `MemoryMigrationJob`, and vector-store dependencies
- Introduced a custom `MemoryMdAdvisor` that injects the `MEMORY.md` index
  into the system message on every turn

Most of issue #255's alignment plan is therefore already satisfied. The only
remaining divergence from the blog pattern is that Herald uses **Option B**
(custom `MemoryMdAdvisor` + baked-in memory system prompt) where the blog
recommends **Option A** (library `AutoMemoryToolsAdvisor` that owns both the
tool registration and the system prompt injection).

> Note: the issue title mentions `AutoAutoMemoryToolsAdvisor` with a doubled
> "Auto" prefix. The actual library class is `AutoMemoryToolsAdvisor` —
> single "Auto". Verified via `javap` on `spring-ai-agent-utils-0.7.0.jar`.

## Goal

Replace Herald's custom `MemoryMdAdvisor` with the library's
`AutoMemoryToolsAdvisor`. The library advisor owns:

1. Per-request registration of the 6 memory tools (via
   `BaseChatMemoryAdvisor.before()`)
2. Injection of a memory system prompt `Resource` on each turn
3. An optional `memoryConsolidationTrigger` (left at default — a no-op)

This removes the need to register `AutoMemoryTools` in the main tool list or
bake `AUTO_MEMORY_SYSTEM_PROMPT.md` into `MAIN_AGENT_SYSTEM_PROMPT.md` at
bean-init time. Memory-related wiring consolidates into a single advisor
entry.

## Non-goals

- Changing the memory taxonomy or the content of
  `AUTO_MEMORY_SYSTEM_PROMPT.md` (except for removing one placeholder).
- Customizing `memoryConsolidationTrigger`. We use the library default.
- Changing how `MEMORY.md` is bootstrapped (the init code that creates an
  empty `MEMORY.md` if missing stays).
- Removing `Memory*` tool names from `activeToolNames`. They're used as a
  "don't shadow these names" filter for tool aliasing, not for registration.
- Modifying the `# Prior Context Lookup` subsection in
  `MAIN_AGENT_SYSTEM_PROMPT.md`. That's Herald-specific behavior guidance
  distinct from the generic memory instructions.

## Library API (verified via `javap`)

From `org/springaicommunity/agent/advisors/AutoMemoryToolsAdvisor.class`
and its nested `Builder`:

```
public class AutoMemoryToolsAdvisor implements BaseChatMemoryAdvisor {
    // Private fields:
    //   int order
    //   String memorySystemPrompt           (loaded once at build time)
    //   List<ToolCallback> memoryToolCallbacks
    //   BiPredicate<ChatClientRequest, Instant> memoryConsolidationTrigger

    public ChatClientRequest before(ChatClientRequest, AdvisorChain);
    public ChatClientResponse after(ChatClientResponse, AdvisorChain);
    public int getOrder();
    public static Builder builder();
}

public final class Builder {
    public Builder order(int);
    public Builder memoriesRootDirectory(String);
    public Builder memorySystemPrompt(Resource);
    public Builder memoryConsolidationTrigger(BiPredicate<ChatClientRequest, Instant>);
    public AutoMemoryToolsAdvisor build();
}
```

Key observations:

- The advisor **owns** its `List<ToolCallback> memoryToolCallbacks`. Passing
  `memoriesRootDirectory(String)` causes it to construct `AutoMemoryTools`
  internally. Herald's separate `AutoMemoryTools.builder()` call becomes
  redundant.
- The `before()` method has lambdas `lambda$before$0` and `lambda$before$1`
  that take a `Set` of strings and a `ToolCallback` — classic "filter out
  tools already present in the request" pattern. So the advisor injects
  only missing memory tools on each request; no duplicates.
- The `memorySystemPrompt` parameter is `Resource` on the builder but stored
  as `String` on the instance — loaded once at build time.
- `memoryConsolidationTrigger`'s builder default is a private lambda
  (`lambda$new$0`) — effectively a no-op. We keep the default.

## Architecture

### Before (current)

```
Init time:
    AUTO_MEMORY_SYSTEM_PROMPT.md (with {MEMORIES_ROOT_DIRECTORY} placeholder)
      └─ substituted → baked into MAIN_AGENT_SYSTEM_PROMPT.md at {long_term_memory_instructions}
           └─ passed as .defaultSystem(systemPrompt) on ChatClient

    AutoMemoryTools.builder().memoriesDir(memoriesDir).build()
      └─ added to buildToolList(...) → .defaultTools(toolList.toArray())

Runtime (each turn):
    Advisor chain:
      ├─ ...
      ├─ MemoryMdAdvisor  ← reads MEMORY.md, appends to system message
      └─ ...
```

### After

```
Init time:
    AUTO_MEMORY_SYSTEM_PROMPT.md (placeholder removed, content unchanged otherwise)
      └─ held as ClassPathResource, passed to .memorySystemPrompt(...) on advisor builder

    MAIN_AGENT_SYSTEM_PROMPT.md (no {long_term_memory_instructions})
      └─ passed as .defaultSystem(systemPrompt) on ChatClient

    AutoMemoryToolsAdvisor.builder()
      .memoriesRootDirectory(memoriesDir.toString())
      .memorySystemPrompt(ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md"))
      .order(HIGHEST_PRECEDENCE + 100)
      .build()
    → added to advisor chain (replacing MemoryMdAdvisor)

    [No standalone AutoMemoryTools instance; no memory entries in defaultTools]

Runtime (each turn):
    Advisor chain:
      ├─ ...
      ├─ AutoMemoryToolsAdvisor  ← before(): injects tools + memory system prompt
      └─ ...
```

## Changes

### Deletions

- `herald-core/src/main/java/com/herald/agent/MemoryMdAdvisor.java` (85 lines)

There is no corresponding `MemoryMdAdvisorTest.java` in tracked code.

### `herald-core/src/main/resources/prompts/AUTO_MEMORY_SYSTEM_PROMPT.md`

Drop the `{MEMORIES_ROOT_DIRECTORY}` placeholder since the library advisor
does not perform placeholder substitution. The agent does not need the
absolute path — all tool calls are relative.

- Before (line 3):
  ```
  You have a persistent, file-based memory system at {MEMORIES_ROOT_DIRECTORY}.
  All paths passed to memory tools are relative to that root.
  ```
- After:
  ```
  You have a persistent, file-based memory system. All paths passed to
  memory tools are relative to the memory root directory.
  ```

No other content in the file changes.

### `herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md`

Remove the `{long_term_memory_instructions}` placeholder (and the extra
blank line left behind) while keeping the `# Memory Management` header and
the `## Prior Context Lookup` subsection intact.

- Before (lines 41-45):
  ```
  # Memory Management

  {long_term_memory_instructions}

  ## Prior Context Lookup
  ```
- After:
  ```
  # Memory Management

  ## Prior Context Lookup
  ```

### `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`

**Imports:**
- Remove: `import org.springaicommunity.agent.tools.AutoMemoryTools;`
- Add: `import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;`

(`MemoryMdAdvisor` lives in the same package as `HeraldAgentConfig`, so no
import to drop for it.)

**`modelSwitcher(...)` parameter list:**
- Remove: `@Value("classpath:prompts/AUTO_MEMORY_SYSTEM_PROMPT.md") Resource memoryPromptResource,`
  (parameter position ~143)

**Inside `modelSwitcher`:**
- Remove lines ~173-175 (the standalone `AutoMemoryTools autoMemoryTools = AutoMemoryTools.builder()...build();` block)
- Remove lines ~177-179 (`String memoryInstructions = loadPromptTemplate(memoryPromptResource).replace("{MEMORIES_ROOT_DIRECTORY}", memoriesDir.toString());`)
- Update line ~181 (`String systemPrompt = resolvePrompt(promptTemplate, config, defaultModel, memoryInstructions);`) to drop the `memoryInstructions` argument. `resolvePrompt` signature changes correspondingly.

**`buildToolList(...)` signature and body:**
- Remove the `AutoMemoryTools autoMemoryTools` parameter
- Remove `tools.add(autoMemoryTools);` from the body
- Update the single call site inside `modelSwitcher` (`buildToolList(autoMemoryTools, shellDecorator, fsTools, ...)`) to drop the `autoMemoryTools` argument

**`resolvePrompt(...)` signature:**
- Before: `String resolvePrompt(String template, HeraldConfig config, String modelId, String memoryInstructions)`
- After: `String resolvePrompt(String template, HeraldConfig config, String modelId)`
- Remove the `.replace("{long_term_memory_instructions}", memoryInstructions)` call from the body

**`buildAdvisorChain(...)`:**
- Replace `advisors.add(new MemoryMdAdvisor(memoriesDir));` with:
  ```java
  advisors.add(AutoMemoryToolsAdvisor.builder()
          .memoriesRootDirectory(memoriesDir.toString())
          .memorySystemPrompt(new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md"))
          .order(Ordered.HIGHEST_PRECEDENCE + 100)
          .build());
  ```

`ClassPathResource` and `Ordered` are already imported in this file.

### `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigTest.java`

- Drop the `new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md")` argument from the single `modelSwitcher(...)` invocation (line ~117).

### `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java`

- Drop the `new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md")` argument from the 4 `modelSwitcher(...)` invocations (lines ~75, ~121, ~158, ~228).
- Update the comment at line ~252 that mentions `MemoryMdAdvisor` in the advisor chain listing. Replace with `AutoMemoryToolsAdvisor`.

### `herald-bot/src/test/java/com/herald/agent/HeraldA2aIntegrationTest.java`

- Drop the `new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md")` argument from the single `modelSwitcher(...)` invocation (line ~106).

## Tests

### Existing tests — confirm no regression

All 23 herald-bot tests must still pass after the migration. In particular:

- `HeraldAgentConfigIntegrationTest` builds the full bean graph — it will
  confirm the advisor chain compiles and the tools resolve correctly.
- `HeraldA2aIntegrationTest` exercises the A2A path — unaffected by this
  change but must continue to pass.
- `HeraldConfigA2aTest` — unaffected.

### Optional new test (recommended)

Add an integration-level assertion in
`HeraldAgentConfigIntegrationTest.modelSwitcherBeanCreatedWithAllToolsAndAdvisors`
that the advisor chain contains exactly one `AutoMemoryToolsAdvisor` and
zero `MemoryMdAdvisor` instances. This locks in the migration.

Implementation: call `switcher.getActiveClient()`, access the default
advisor chain via reflection or a test-visible getter, walk the list, and
count. If reflection is required and the fixture becomes brittle, drop this
test and rely on the existing integration tests for signal.

## Verification

- Build:
  `./mvnw -pl herald-bot,herald-core,herald-persistence,herald-telegram -am clean verify`
  Expect BUILD SUCCESS, all tests green.
- Grep for leftover references:
  ```
  MemoryMdAdvisor       → should hit nothing in tracked code
  memoryInstructions    → should hit nothing
  memoryPromptResource  → should hit nothing
  {long_term_memory_instructions} → should hit nothing in tracked code
  {MEMORIES_ROOT_DIRECTORY}       → should hit nothing in tracked code
  ```
  Worktree copies under `.claude/worktrees/` do not count.
- Manual smoke (optional): start herald-bot, ask "what memories do you
  have about me?", verify the agent uses `MemoryView` to enumerate files
  and responds with content from `MEMORY.md`.

## Risks

1. **Tool registration timing.** `AutoMemoryToolsAdvisor` registers memory
   tools at request time via `before()`, not at `ChatClient` build time. If
   any downstream code inspects `ChatClient.defaultTools()` expecting
   memory tools there, that assumption breaks. Current Herald code does
   not make this assumption (verified by grep for `getDefaultTools` /
   `toolCallbacks`), but the integration test provides the authoritative
   signal.

2. **Silent behavior change from library default `memoryConsolidationTrigger`.**
   The builder's default is a private lambda (`lambda$new$0`). If a future
   library release changes this default to something aggressive, Herald
   could see unexpected memory churn. Mitigation: explicitly pass a no-op
   lambda `(req, instant) -> false` to lock in current behavior. Added to
   the Task 3 wiring below.

3. **System prompt duplication if migration is incomplete.** If the
   placeholder is removed from `MAIN_AGENT_SYSTEM_PROMPT.md` but the
   advisor is not added (or vice versa), the agent either sees memory
   instructions twice or not at all. Mitigation: the implementation plan
   bundles all four edits into one task so they land together.

4. **`memorySystemPrompt` `Resource` loading is eager.** The advisor's
   builder reads the `Resource` once at `build()` time and stores the
   content as a `String`. Changes to `AUTO_MEMORY_SYSTEM_PROMPT.md` on
   disk (e.g., from an IDE save while the bot is running) will NOT hot-
   reload. Herald's previous `MemoryMdAdvisor` read `MEMORY.md` (the
   dynamic index) every turn, but it never hot-reloaded the instructions
   either — so this is not a regression, just worth documenting.

## Out of scope

- Customizing `memoryConsolidationTrigger` beyond an explicit no-op
- Changing the memory taxonomy or the content of AUTO_MEMORY_SYSTEM_PROMPT.md
  (only the placeholder line is touched)
- Updating `activeToolNames` to remove memory tool names (they stay as
  alias collision avoidance)
- Changing `MEMORY.md` bootstrap logic
- Renaming or moving prompt resource files
