# Issue #253 — Remove HeraldSubagentFactory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the zero-logic `HeraldSubagentFactory` wrapper, call `ClaudeSubagentType.builder()` directly from `HeraldAgentConfig`, and pass Herald's skills directory into every subagent.

**Architecture:** `HeraldAgentConfig.modelSwitcher` currently builds its subagent type through `HeraldSubagentFactory.builder()`, which is a thin delegate over `ClaudeSubagentType.Builder` from `spring-ai-agent-utils`. This plan removes the wrapper, replaces the single call site, and chains `.skillsDirectories(reloadableSkillsTool.getSkillsDirectory())` onto the builder so subagents receive the same skills as the main agent.

**Tech Stack:** Java 21 · Spring Boot · spring-ai-agent-utils 0.7.0 · Maven multi-module (herald-bot, herald-core, herald-telegram)

**Spec:** `docs/superpowers/specs/2026-04-10-issue-253-subagent-factory-removal.md`

---

## File Structure

**Files to delete (2)**
- `herald-core/src/main/java/com/herald/agent/subagent/HeraldSubagentFactory.java`
- `herald-core/src/test/java/com/herald/agent/subagent/HeraldSubagentFactoryTest.java`

The package directory `herald-core/src/main/java/com/herald/agent/subagent/` stays — `HeraldSubagentReferences.java` continues to live there.

**Files to modify (3)**
- `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` — swap wrapper for direct `ClaudeSubagentType.builder()`, add `.skillsDirectories(...)`
- `README.md` — update the directory-tree comment that lists `HeraldSubagentFactory`
- `docs/module-inventory.md` — remove the `HeraldSubagentFactory` row

---

## Task 1: Rewire HeraldAgentConfig to call ClaudeSubagentType directly

This is the core behavior change. It must land before Task 2 (deleting the wrapper), because removing `HeraldSubagentFactory.java` while it is still referenced would break compilation.

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`

**Current relevant snippets** (line numbers approximate; use the exact old_string blocks below):

Import (line ~17):
```java
import com.herald.agent.subagent.HeraldSubagentFactory;
```

Subagent builder block (lines ~188-203):
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

- [ ] **Step 1: Swap the import**

Use the Edit tool on `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`.

`HeraldSubagentReferences` is already imported on the next line, so only `HeraldSubagentFactory` needs to be replaced.

- old_string:
```
import com.herald.agent.subagent.HeraldSubagentFactory;
```
- new_string:
```
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
```

The resulting import block will still be in valid Java package order because `com.herald.*` already sorts above `org.springaicommunity.*` in the file's existing grouping. (If the IDE's formatter moves the new line elsewhere, that is fine — position doesn't affect correctness.)

- [ ] **Step 2: Rewrite the subagent builder block**

Use the Edit tool:

- old_string:
```
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
- new_string:
```
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

- [ ] **Step 3: Compile `herald-bot` to confirm the rewrite builds**

Run:
```bash
./mvnw -pl herald-bot -am test-compile -q
```

Expected: BUILD SUCCESS (exit 0).

`test-compile` is used here (not just `compile`) to catch any test-side usages of `HeraldSubagentFactory` in `herald-bot`'s own tests. The previous issue's Task 2 learned this lesson: `mvnw compile` only touches `src/main`, missing test-side breakage.

If compilation fails with `cannot find symbol: class HeraldSubagentFactory` in a test file, locate it with:
```
Grep tool: pattern `HeraldSubagentFactory`, glob `herald-bot/src/test/**/*.java`
```
and update those test files to use `ClaudeSubagentType.builder()` instead. (Note: at the time this plan was written, a repo-wide grep showed `HeraldSubagentFactory` only in production code, `herald-core` tests, and docs — but verify.)

- [ ] **Step 4: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git commit -m "refactor: call ClaudeSubagentType.builder directly with skills directory (#253)"
```

---

## Task 2: Delete HeraldSubagentFactory and its test

Now that `HeraldAgentConfig` no longer references it, the wrapper class and its test can be removed.

**Files:**
- Delete: `herald-core/src/main/java/com/herald/agent/subagent/HeraldSubagentFactory.java`
- Delete: `herald-core/src/test/java/com/herald/agent/subagent/HeraldSubagentFactoryTest.java`

- [ ] **Step 1: Delete the test file**

```bash
rm herald-core/src/test/java/com/herald/agent/subagent/HeraldSubagentFactoryTest.java
```

- [ ] **Step 2: Delete the production file**

```bash
rm herald-core/src/main/java/com/herald/agent/subagent/HeraldSubagentFactory.java
```

- [ ] **Step 3: Verify no lingering references in production or test code**

Use the Grep tool:
- Pattern: `HeraldSubagentFactory`
- Glob: `**/src/**/*.java`

Expected: no results. Stale worktree copies under `.claude/worktrees/**` do not count — they're not tracked by git.

If any reference remains in a Java file under `src/**`, open that file and either remove the reference or replace it with `ClaudeSubagentType.builder()` as appropriate.

- [ ] **Step 4: Build the three affected modules together**

Run:
```bash
./mvnw -pl herald-bot,herald-core,herald-telegram -am verify -q
```

Expected: BUILD SUCCESS, all tests green. This is a full verify (not just compile) because we just deleted a test file — we want the test reactor to confirm the deletion didn't leave any dangling test infrastructure.

- [ ] **Step 5: Commit**

```bash
git add -A herald-core/src/main/java/com/herald/agent/subagent/HeraldSubagentFactory.java \
            herald-core/src/test/java/com/herald/agent/subagent/HeraldSubagentFactoryTest.java
git commit -m "refactor: delete HeraldSubagentFactory wrapper (#253)"
```

---

## Task 3: Update documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/module-inventory.md`

- [ ] **Step 1: Update the directory-tree comment in README.md**

The current line (line ~380) references `HeraldSubagentFactory` in an ASCII directory tree comment.

Use the Edit tool on `README.md`:

- old_string:
```
│       │   └── subagent/            # HeraldSubagentFactory, HeraldSubagentReferences
```
- new_string:
```
│       │   └── subagent/            # HeraldSubagentReferences
```

- [ ] **Step 2: Remove the HeraldSubagentFactory row from docs/module-inventory.md**

The current row (line ~85) describes `HeraldSubagentFactory`.

Use the Edit tool on `docs/module-inventory.md`:

- old_string:
```
| HeraldSubagentFactory | com.herald.agent.subagent | Core | Model-agnostic builder wrapping ClaudeSubagentType; no DB dependency |
```
- new_string: (empty — delete the entire line)

- [ ] **Step 3: Verify no stray references in docs/README**

Use the Grep tool:
- Pattern: `HeraldSubagentFactory`
- Glob: `{README.md,docs/**/*.md}`

Expected hits only:
- `docs/superpowers/specs/2026-04-10-issue-253-subagent-factory-removal.md` (this issue's spec)
- `docs/superpowers/plans/2026-04-10-issue-253-subagent-factory-removal.md` (this plan)
- Possibly `docs/superpowers/plans/2026-03-20-generic-subagent-type.md` (historical plan that originally introduced the factory — leave untouched; historical plans are append-only)

Any other hit means a doc was missed — Edit it.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/module-inventory.md
git commit -m "docs: remove HeraldSubagentFactory references (#253)"
```

---

## Task 4: Final verification

- [ ] **Step 1: Clean build of affected modules**

Run:
```bash
./mvnw -pl herald-bot,herald-core,herald-telegram -am verify
```

Expected: BUILD SUCCESS, all tests green. If this is the first full verify in the session it may take a couple of minutes.

- [ ] **Step 2: Full-repo grep for any stragglers**

Use the Grep tool:
- Pattern: `HeraldSubagentFactory`
- Glob: (omit — search everything)

Expected hits only in:
- `docs/superpowers/specs/2026-04-10-issue-253-*.md` (the spec — self-references)
- `docs/superpowers/plans/2026-04-10-issue-253-*.md` (this plan — self-references)
- `docs/superpowers/plans/2026-03-20-generic-subagent-type.md` (historical plan, intentionally preserved)
- `.claude/worktrees/**` (stale worktree copies, not tracked by git — ignore)

If any other hit appears, resolve it before proceeding.

- [ ] **Step 3: Confirm issue #253 acceptance criteria**

Re-read the "Alignment Plan" in `gh issue view 253`:

1. ✅ Delete `herald-core/src/main/java/com/herald/agent/subagent/HeraldSubagentFactory.java` → Task 2 Step 2
2. ✅ In `HeraldAgentConfig`, use `ClaudeSubagentType.builder()` directly → Task 1 Step 2
3. ✅ Add `.skillsDirectories(...)` (the spec uses the `String` overload with `reloadableSkillsTool.getSkillsDirectory()`, which is semantically equivalent to the issue's `.skillsResources(skillPaths)` suggestion) → Task 1 Step 2
4. ✅ Review `.claude/agents/` for duplicates with library built-ins → resolved during brainstorming: only `research.md` exists, no conflict with `general-purpose` / `Explore` / `Plan` / `code-reviewer`, no action needed

- [ ] **Step 4: No new commit needed**

This task is verification only.
