# Part 1 Agent Skills — Completion Design Spec

**Date:** 2026-04-15
**Scope:** Close the four `➖ Not Fully Implemented` items in Part 1 of `docs/herald-patterns-comparison.md`
**Approach:** Approach A (Incremental) — approved by user

---

## Overview

Part 1 of the Spring AI Agentic Patterns blog series defines seven features for Agent Skills. Herald currently has 3 adopted and 2 enhanced. Four are marked `➖ Not Fully Implemented`:

1. **Classpath / JAR packaging support** — already implemented, needs reclassification
2. **Anthropic Native Skills** — already implemented, needs reclassification
3. **Dynamic Self-Teaching (Auto-Skill Generation)** — needs new code
4. **Formal Human-in-the-Loop (HITL) for Skill Execution** — needs new code

After this work, Part 1 will have 5 ✅ Implemented, 2 ➕ Enhanced, and 0 ➖.

---

## Item 1: Reclassify Classpath Skill Loading

### Current State

Already working. `ReloadableSkillsTool(String skillsDirectory, List<Resource> classpathResources)` accepts classpath resources. `HeraldAgentConfig.reloadableSkillsTool()` scans `classpath:skills/*/SKILL.md` via `ResourcePatternResolver` and passes parent directories as `FileSystemResource` objects. The `system-info` skill at `herald-core/src/main/resources/skills/system-info/SKILL.md` proves this works.

### Changes Required

- **`docs/herald-patterns-comparison.md`**: Change the "Classpath / JAR packaging support" entry from `➖ Not Fully Implemented` to `✅ Implemented`. Update the description to reflect reality:

  > **Herald:** ✅ **Implemented.** `ReloadableSkillsTool` accepts both filesystem and classpath `Resource` sources. `HeraldAgentConfig` scans `classpath:skills/*/SKILL.md` at startup via `ResourcePatternResolver`. The built-in `system-info` skill demonstrates classpath packaging.

- **Summary table**: Adjust Part 1 counts.

---

## Item 2: Reclassify Anthropic Native Skills

### Current State

Infrastructure fully wired. `HeraldConfig.Agent` declares `List<String> anthropicSkills`. `ModelSwitcher.chatOptionsForModel()` iterates the list and calls `AnthropicChatOptions.builder().skill(skillId)` for each. The main agent client applies these skills; subagents intentionally do not (`List.of()`). Config property: `herald.agent.anthropic-skills`.

### Changes Required

- **`docs/herald-patterns-comparison.md`**: Change the "Anthropic Native Skills" entry from `➖ Not Fully Implemented` to `✅ Implemented`. Update the description:

  > **Herald:** ✅ **Implemented.** `HeraldConfig.anthropicSkills()` accepts a list of Anthropic skill IDs (e.g., `computer_use`, `code_execution`). `ModelSwitcher` applies them via `AnthropicChatOptions.builder().skill()` to the main agent. Subagents intentionally exclude native skills. Configure via `herald.agent.anthropic-skills` or `HERALD_AGENT_ANTHROPIC_SKILLS` env var.

- **Summary table**: Adjust Part 1 counts.

---

## Item 3: Dynamic Self-Teaching (Auto-Skill Generation)

### Problem

The agent has filesystem access and skills hot-reload, but doesn't know it can write skills. There's no validation to catch malformed frontmatter before it's persisted.

### Design

Two components:

#### 3a. ValidateSkillTool

**New file:** `herald-core/src/main/java/com/herald/agent/ValidateSkillTool.java`

A `@Tool`-annotated Spring component that validates SKILL.md content before the agent writes it.

**Constructor:**
```java
public ValidateSkillTool(@Value("${herald.agent.skills-directory:skills}") String skillsDirectory)
```

Resolves `~` in the skills directory path (same logic as `ReloadableSkillsTool`).

**Tool method:**
```java
@Tool(description = "Validate a SKILL.md file before writing it to the skills directory. "
    + "Pass the full content of the SKILL.md you intend to write. "
    + "Returns OK if valid, or a list of errors to fix.")
public String validateSkill(
    @ToolParam(description = "The full content of the SKILL.md file to validate") String content)
```

**Validation rules:**
1. Content starts with `---` (YAML frontmatter delimiter).
2. Closing `---` delimiter exists after the opening one.
3. Frontmatter parses as valid YAML (use `org.yaml.snakeyaml.Yaml` — already a transitive dependency via Spring Boot).
4. `name` field is present, non-empty, matches `^[a-z][a-z0-9-]*$`.
5. `description` field is present and non-empty.
6. If `allowed-tools` is present, it parses correctly via `ReloadableSkillsTool.parseStringOrList()` (make that method `public static`).
7. If `model` is present, it's a non-empty string.
8. If `requires-approval` is present, it parses as a boolean.
9. Markdown body after closing `---` is non-empty (at least one non-whitespace character).

**Return value:**
- On success: `"OK — skill '<name>' is valid and ready to write to: <skillsDirectory>/<name>/SKILL.md"`
- On failure: Numbered list of errors, e.g.:
  ```
  Validation failed:
  1. Missing required field: name
  2. description is empty
  3. Markdown body after frontmatter is empty
  ```

**Registration in HeraldAgentConfig:**
- Create as a `@Bean` or let component scan pick it up (prefer `@Component` since it has no complex wiring).
- Add to `buildToolList()` alongside existing tools.
- Add `"validateSkill"` to `activeToolNames`.

#### 3b. System Prompt Addition

**File to modify:** `herald-bot/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md`

Add a new section (after existing tool guidance, before any closing sections):

```markdown
## Self-Teaching — Creating New Skills

You can permanently learn new capabilities by creating skills. When a user teaches you
a multi-step workflow, a new API integration, or any repeatable task you expect to do
again, offer to save it as a skill.

**Workflow:**
1. Draft the SKILL.md content.
2. Call `validateSkill` to check it.
3. Fix any reported errors.
4. Use `filesystem` to create the directory and write the file to `{skills_directory}/<skill-name>/SKILL.md`.
5. The skill is available immediately — hot-reload picks it up within seconds.

**SKILL.md format:**
```
---
name: skill-name
description: >
  What this skill does and when to use it.
allowed-tools: shell, web    # optional — restrict tools during execution
model: claude-sonnet-4-5          # optional — preferred model
requires-approval: false     # optional — require user approval before execution
---

# Skill Title

Markdown instructions for executing the skill...
```

**When to create a skill:**
- The user explicitly asks you to remember a workflow
- You've performed the same multi-step task more than once
- A complex API or CLI pattern would benefit from documented steps

**When NOT to create a skill:**
- One-off tasks unlikely to recur
- Simple commands that don't need documentation
- Tasks that are already covered by an existing skill
```

The `{skills_directory}` placeholder is resolved at prompt-build time. `HeraldAgentConfig.resolvePrompt()` currently accepts `(String template, HeraldConfig config, String modelId)`. Add a fourth parameter `String skillsDirectory` and a new replacement: `.replace("{skills_directory}", skillsDirectory)`. The caller in `modelSwitcher()` already has `skillsDirectory` from `@Value("${herald.agent.skills-directory:skills}")` — pass it through. The tilde path must be resolved before replacement (use `resolveTildePath()`).

---

## Item 4: Formal Human-in-the-Loop (HITL) for Skill Execution

### Problem

Skills execute without user confirmation. The blog explicitly calls out "No Human-in-the-Loop" as a limitation. Herald's shell decorator has a confirmation pattern, but it's not reusable.

### Design

Two components:

#### 4a. ApprovalGate — Shared HITL Abstraction

**New file:** `herald-core/src/main/java/com/herald/agent/ApprovalGate.java`

A Spring `@Component` that provides a reusable approval mechanism over Telegram.

**Constructor:**
```java
@Autowired
public ApprovalGate(
    Optional<MessageSender> messageSender,
    @Value("${herald.agent.approval-timeout-seconds:60}") int timeoutSeconds)
```

**Internal state:**
```java
private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();
```

**Public methods:**

```java
/**
 * Request user approval via Telegram. Blocks until approved, denied, or timeout.
 * @param description Human-readable description of what needs approval
 * @return "APPROVED", "DENIED", or "TIMEOUT"
 */
public String requestApproval(String description)
```

Implementation:
1. Generate `UUID.randomUUID().toString()` as `approvalId`.
2. Create `CompletableFuture<Boolean>`, store in `pendingApprovals`.
3. Send via `MessageSender`: `"Approval required:\n<description>\nReply: /confirm <approvalId> yes  OR  /confirm <approvalId> no"`.
4. If `MessageSender` is null, remove future, return `"DENIED"` with a log warning.
5. Block on `future.get(timeoutSeconds, TimeUnit.SECONDS)`.
6. Return `"APPROVED"` / `"DENIED"` / `"TIMEOUT"`.
7. `finally` block removes from map.

```java
/**
 * Resolve a pending approval. Called by TelegramPoller when user responds.
 * @return true if the approvalId was found and resolved
 */
public boolean resolve(String approvalId, boolean approved)
```

Implementation: look up future in map, call `future.complete(approved)`, return whether it was found.

#### 4b. Refactor HeraldShellDecorator

**File:** `herald-persistence/src/main/java/com/herald/tools/HeraldShellDecorator.java`

**Changes:**
- Add `ApprovalGate` as a constructor parameter (injected by Spring).
- Remove: `pendingConfirmations` field, `confirmCommand()` method, `handleConfirmation()` private method.
- Replace `handleConfirmation(command)` call with:
  ```java
  String result = approvalGate.requestApproval("Shell command: " + redactForLog(command));
  if ("APPROVED".equals(result)) {
      return delegate.execute(command);
  }
  return result.equals("TIMEOUT")
      ? "TIMEOUT: Confirmation timed out. Command was not executed: " + redactForLog(command)
      : "DENIED: Command was rejected by user: " + redactForLog(command);
  ```
- Remove the `confirmCommand()` public method entirely (callers now use `approvalGate.resolve()`).

**Test constructor** (package-private, for unit tests):
```java
HeraldShellDecorator(ShellSecurityConfig securityConfig, ApprovalGate approvalGate)
```

#### 4c. Wire Approval into ReloadableSkillsTool

**File:** `herald-core/src/main/java/com/herald/agent/ReloadableSkillsTool.java`

**New constructor parameters:**
```java
public ReloadableSkillsTool(String skillsDirectory, List<Resource> classpathResources,
                            ApprovalGate approvalGate, List<String> skillsRequiringApproval)
```

Existing constructors delegate to this one with `null` / `List.of()` defaults for backwards compatibility.

**New method:**
```java
public boolean requiresApproval(String skillName) {
    // Check config list
    if (skillsRequiringApproval.contains(skillName)) return true;
    // Check frontmatter
    if (currentSkills == null) return false;
    return currentSkills.stream()
        .filter(s -> s.name().equals(skillName))
        .findFirst()
        .map(s -> s.frontMatter().get("requires-approval"))
        .map(v -> Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v)))
        .orElse(false);
}
```

**Modify `call()` method:**

Insert approval check after skill name extraction, before `delegate.call()`:

```java
@Override
public String call(String toolInput) {
    if (delegate == null) {
        return "No skills are currently loaded.";
    }

    String skillName = extractSkillName(toolInput);

    // HITL approval gate
    if (skillName != null && requiresApproval(skillName) && approvalGate != null) {
        String approval = approvalGate.requestApproval("Execute skill: " + skillName);
        if (!"APPROVED".equals(approval)) {
            return "DENIED: Skill '" + skillName + "' requires user approval. Status: " + approval;
        }
    }

    String result = delegate.call(toolInput);
    // ... existing allowed-tools logic ...
}
```

#### 4d. Configuration

**New properties** (added to `application.yaml` with defaults):

```yaml
herald:
  agent:
    approval-timeout-seconds: 60
    skills-requiring-approval: []
```

**HeraldConfig mapping:**

**File:** `herald-core/src/main/java/com/herald/config/HeraldConfig.java`

Add `skillsRequiringApproval` to the `Agent` inner record (line 42-44):

```java
public record Agent(String persona, String systemPromptExtra, String contextFile,
                    Integer maxContextTokens, String defaultProvider,
                    List<String> anthropicSkills,
                    List<String> skillsRequiringApproval) {
}
```

Add a convenience accessor on the outer `HeraldConfig` (following the `anthropicSkills()` pattern):

```java
public List<String> skillsRequiringApproval() {
    if (agent != null && agent.skillsRequiringApproval() != null) {
        return agent.skillsRequiringApproval();
    }
    return List.of();
}
```

#### 4e. /confirm Command in CommandHandler

**Discovery:** The `/confirm` command is **not yet wired**. `HeraldShellDecorator.confirmCommand()` has a TODO comment: "Wire into TelegramPoller to handle user YES/NO responses." Currently, shell confirmations send the `/confirm` message to Telegram but there's no handler to process the user's reply.

**File to modify:** `herald-telegram/src/main/java/com/herald/telegram/CommandHandler.java`

**Changes:**
1. Add `ApprovalGate` as a constructor parameter.
2. Add a new case in the `handle()` switch: `case "/confirm" -> handleConfirm(parts);`
3. Implement `handleConfirm(String[] parts)`:
   ```java
   private void handleConfirm(String[] parts) {
       if (parts.length < 3) {
           sender.sendMessage("Usage: /confirm <id> yes|no");
           return;
       }
       String approvalId = parts[1];
       boolean approved = "yes".equalsIgnoreCase(parts[2]);
       boolean resolved = approvalGate.resolve(approvalId, approved);
       if (!resolved) {
           sender.sendMessage("No pending approval found for ID: " + approvalId);
       }
       // No confirmation message needed — the blocked thread will handle the response
   }
   ```
4. Add `/confirm` to the `/help` output.

**Also update `TelegramPoller`:** The existing flow in `processUpdate()` checks `questionHandler.hasPendingQuestion()` before routing to `agentService.chat()`. The `/confirm` command is already handled before this check (slash commands are processed first at line 120), so no changes needed to `TelegramPoller` itself.

---

## Item 5: Documentation Updates

After implementation, update `docs/herald-patterns-comparison.md`:

### Part 1 entries to change:

| Feature | Old Status | New Status | New Description |
|---|---|---|---|
| Classpath / JAR packaging | ➖ Not Fully Implemented | ✅ Implemented | Uses `ResourcePatternResolver` + classpath scanning |
| Anthropic Native Skills | ➖ Not Fully Implemented | ✅ Implemented | `AnthropicChatOptions.builder().skill()` wired via config |
| Dynamic Self-Teaching | ➖ Not Fully Implemented | ➕ Enhanced | Goes beyond blog (agent writes own skills + validation + hot-reload) |
| HITL for Skill Execution | ➖ Not Fully Implemented | ➕ Enhanced | Addresses blog's stated limitation with shared `ApprovalGate` |

### Updated summary table row:

```
| Part 1: Agent Skills | 7 | 5 (format, discovery, matching, classpath, native skills) | 4 (execution w/ guardrails, hot reload, self-teaching, HITL) | 0 |
```

Note: Self-Teaching and HITL are `➕ Enhanced` because they go beyond what the blog describes — the blog doesn't specify self-teaching at all, and explicitly calls HITL a limitation.

---

## Files Changed Summary

| File | Action | Description |
|---|---|---|
| `herald-core/.../ApprovalGate.java` | **Create** | Shared HITL approval abstraction |
| `herald-core/.../ValidateSkillTool.java` | **Create** | SKILL.md validation tool |
| `herald-core/.../ReloadableSkillsTool.java` | **Modify** | Add approval gate, `requiresApproval()`, make `parseStringOrList` public static |
| `herald-persistence/.../HeraldShellDecorator.java` | **Modify** | Delegate to `ApprovalGate`, remove internal confirmation logic |
| `herald-core/.../HeraldConfig.java` | **Modify** | Add `skillsRequiringApproval` field |
| `herald-bot/.../HeraldAgentConfig.java` | **Modify** | Wire `ApprovalGate`, `ValidateSkillTool`, new config into beans |
| `herald-bot/.../resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md` | **Modify** | Add self-teaching section |
| `herald-bot/.../resources/application.yaml` | **Modify** | Add `approval-timeout-seconds` and `skills-requiring-approval` defaults |
| `herald-telegram/.../CommandHandler.java` | **Modify** | Add `/confirm` command routing to `ApprovalGate.resolve()` |
| `herald-persistence/.../test/.../HeraldShellDecoratorTest.java` | **Modify** | Update tests: replace `dec.confirmCommand()` calls with `approvalGate.resolve()` |
| `docs/herald-patterns-comparison.md` | **Modify** | Reclassify 4 features, update summary table |

---

## Out of Scope

- A2A server endpoint (Part 5)
- Memory consolidation trigger (Part 6)
- MCP Elicitation (Part 2)
- Parallel subagent execution (Part 4)
- Tests (will be defined in the implementation plan, not the spec)
