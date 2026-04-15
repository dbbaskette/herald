# Issue #253 — Remove HeraldSubagentFactory wrapper

**Issue:** [dbbaskette/herald#253](https://github.com/dbbaskette/herald/issues/253)
**Status:** Design approved 2026-04-10

## Background

Herald wires multi-model subagent routing through
`HeraldSubagentFactory`, a 34-line wrapper in
`herald-core/src/main/java/com/herald/agent/subagent/HeraldSubagentFactory.java`
whose `Builder` does nothing but delegate every call to
`ClaudeSubagentType.Builder` from `spring-ai-agent-utils`. Its only consumer
is `HeraldAgentConfig.modelSwitcher`.

Two gaps flagged in issue #253:

1. **The wrapper is dead weight.** It adds no Herald-specific behavior, obscures
   the upstream API, and blocks access to new builder methods
   (e.g. `skillsDirectories`, `skillsResources`, `braveApiKey`).
2. **Subagents do not receive skills.** The library's
   `ClaudeSubagentType.Builder` can pass a skills directory into every
   subagent's system prompt via `.skillsDirectories(String)`. Herald's main
   agent already loads skills from a configured directory
   (`herald.agent.skills-directory`, default `skills`), but none of the
   subagents see them.

A third concern in the issue — that `.claude/agents/*.md` might shadow the
library's built-in `general-purpose`, `Explore`, `Plan`, and `code-reviewer`
agents — was checked during context gathering. `.claude/agents/` contains only
`research.md`, which does not conflict with any built-in. No audit action is
required.

## Goal

Delete `HeraldSubagentFactory` (and its test), call `ClaudeSubagentType.builder()`
directly from `HeraldAgentConfig`, and pass the skills directory into every
subagent via `.skillsDirectories(...)`.

## Non-goals

- Changing `.claude/agents/research.md` or the `HeraldSubagentReferences`
  loader. The loader still lives in the same package and is unaffected.
- Changing `TaskTool` / `TaskOutputTool` wiring, `ModelSwitcher`, or the
  multi-model routing.
- Introducing new tests. The deleted test file (`HeraldSubagentFactoryTest`)
  only exercised the wrapper's delegate behavior, which no longer exists.
  `HeraldAgentConfigIntegrationTest` already validates that `modelSwitcher`
  successfully builds the bean graph.

## Library API

Confirmed via `javap` on
`org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType$Builder`
(from `spring-ai-agent-utils 0.7.0`):

```
public Builder chatClientBuilder(String, ChatClient.Builder)
public Builder chatClientBuilders(Map<String, ChatClient.Builder>)
public Builder skillsResources(List<Resource>)
public Builder skillsResource(Resource)
public Builder skillsDirectories(List<String>)
public Builder skillsDirectories(String)          // single-directory overload
public Builder braveApiKey(String)
public SubagentType build()
```

The `skillsDirectories(String)` overload is the right fit: Herald already has
a single skills directory as a String, and `ReloadableSkillsTool` exposes its
tilde-expanded absolute path via `getSkillsDirectory()`.

## Architecture

### Before

```
HeraldAgentConfig.modelSwitcher
  └─ HeraldSubagentFactory.builder()
       └─ ClaudeSubagentType.builder()            // every call passed through
            ├─ chatClientBuilder(default, ...)
            ├─ chatClientBuilder(haiku, ...)
            ├─ chatClientBuilder(sonnet, ...)
            ├─ chatClientBuilder(opus, ...)
            ├─ (openai/ollama/gemini/lmstudio if present)
            └─ build()
  [skills are NOT passed to subagents]
```

### After

```
HeraldAgentConfig.modelSwitcher
  └─ ClaudeSubagentType.builder()                 // direct, no wrapper
       ├─ chatClientBuilder(default, ...)
       ├─ chatClientBuilder(haiku, ...)
       ├─ chatClientBuilder(sonnet, ...)
       ├─ chatClientBuilder(opus, ...)
       ├─ (openai/ollama/gemini/lmstudio if present)
       ├─ skillsDirectories(reloadableSkillsTool.getSkillsDirectory())   // NEW
       └─ build()
```

## Changes

### Deletions

- `herald-core/src/main/java/com/herald/agent/subagent/HeraldSubagentFactory.java`
- `herald-core/src/test/java/com/herald/agent/subagent/HeraldSubagentFactoryTest.java`

The `herald-core/src/main/java/com/herald/agent/subagent/` directory stays
because `HeraldSubagentReferences.java` continues to live there.

### `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`

- Remove `import com.herald.agent.subagent.HeraldSubagentFactory;`
- Add `import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;`
- In `modelSwitcher`, rewrite the subagent-builder block:
  - Replace `HeraldSubagentFactory.builder()` with `ClaudeSubagentType.builder()`
  - After all `chatClientBuilder(...)` calls and before `.build()`, call
    `.skillsDirectories(reloadableSkillsTool.getSkillsDirectory())`
  - `reloadableSkillsTool` is already injected into `modelSwitcher` as a
    parameter, so no new method parameter is required.

Before (approx. lines 188-203):

```java
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
        lmstudioChatModel.ifPresent(model ->
                subagentTypeBuilder.chatClientBuilder("lmstudio", chatClientBuilderForModel(model, lmstudioModel)));

        var subagentType = subagentTypeBuilder.build();
```

After:

```java
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
        lmstudioChatModel.ifPresent(model ->
                subagentTypeBuilder.chatClientBuilder("lmstudio", chatClientBuilderForModel(model, lmstudioModel)));

        var subagentType = subagentTypeBuilder
                .skillsDirectories(reloadableSkillsTool.getSkillsDirectory())
                .build();
```

### Documentation

- `README.md` — remove any mention of `HeraldSubagentFactory`; note that
  subagents now receive the skills directory.
- `docs/module-inventory.md` — remove the `HeraldSubagentFactory` row.

## Risks

- **Subagent prompt size.** Every subagent's system prompt grows by the size
  of the skills index. At the current skill count this is negligible, but the
  change is intentional — this is the blog pattern.
- **Path resolution timing.** `ReloadableSkillsTool.getSkillsDirectory()`
  returns the tilde-expanded path set at bean construction, which is stable
  by the time `modelSwitcher` builds the subagent type.
- **Public API surface.** `HeraldSubagentFactory` lives in `herald-core` and
  is technically public. No module outside `herald-bot` uses it (verified by
  grep). Its removal is safe.

## Verification

- Module build:
  `./mvnw -pl herald-bot,herald-core,herald-telegram -am verify`
  Expect BUILD SUCCESS, all tests green.
- Grep for leftover references:
  `HeraldSubagentFactory` should hit only this spec/plan after the refactor,
  plus stale worktree copies under `.claude/worktrees/**` (ignored).
- Manual smoke: run herald-bot, invoke a subagent (the custom `research`
  agent in `.claude/agents/research.md` or a library built-in such as
  `general-purpose`), confirm it starts cleanly and the skills index appears
  in its context.
