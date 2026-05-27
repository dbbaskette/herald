# Part 1 Agent Skills Completion — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all four `➖ Not Fully Implemented` items in Part 1 of the herald-patterns-comparison by reclassifying two already-implemented features and building two new ones (ApprovalGate + ValidateSkillTool).

**Architecture:** Extract a shared `ApprovalGate` from the existing shell confirmation pattern, wire it into both `HeraldShellDecorator` (refactor) and `ReloadableSkillsTool` (new), add a `ValidateSkillTool` for agent-authored skills, and teach the agent about self-teaching via system prompt.

**Tech Stack:** Java 21, Spring Boot 4.0.0, Spring AI 2.0.0-SNAPSHOT, spring-ai-agent-utils 0.7.0, SnakeYAML (transitive), AssertJ + Mockito for tests, Maven.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `herald-core/src/main/java/com/herald/agent/ApprovalGate.java` | **Create** | Shared HITL approval: UUID-keyed futures, Telegram prompt, timeout |
| `herald-core/src/test/java/com/herald/agent/ApprovalGateTest.java` | **Create** | Unit tests for ApprovalGate |
| `herald-core/src/main/java/com/herald/agent/ValidateSkillTool.java` | **Create** | `@Tool` that validates SKILL.md content before writing |
| `herald-core/src/test/java/com/herald/agent/ValidateSkillToolTest.java` | **Create** | Unit tests for ValidateSkillTool |
| `herald-core/src/main/java/com/herald/agent/ReloadableSkillsTool.java` | **Modify** | Add `ApprovalGate` + `skillsRequiringApproval`, `requiresApproval()`, make `parseStringOrList` public |
| `herald-core/src/test/java/com/herald/agent/ReloadableSkillsToolApprovalTest.java` | **Create** | Tests for approval gating in ReloadableSkillsTool |
| `herald-persistence/src/main/java/com/herald/tools/HeraldShellDecorator.java` | **Modify** | Delegate to `ApprovalGate`, remove internal confirmation state |
| `herald-persistence/src/test/java/com/herald/tools/HeraldShellDecoratorTest.java` | **Modify** | Update confirmation tests to use `ApprovalGate` |
| `herald-core/src/main/java/com/herald/config/HeraldConfig.java` | **Modify** | Add `skillsRequiringApproval` to `Agent` record + accessor |
| `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` | **Modify** | Wire `ApprovalGate`, `ValidateSkillTool`, pass config to beans |
| `herald-telegram/src/main/java/com/herald/telegram/CommandHandler.java` | **Modify** | Add `/confirm` command |
| `herald-telegram/src/test/java/com/herald/telegram/CommandHandlerTest.java` | **Modify** | Add `/confirm` tests |
| `herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md` | **Modify** | Add self-teaching section |
| `herald-bot/src/main/resources/application.yaml` | **Modify** | Add new config properties |
| `docs/herald-patterns-comparison.md` | **Modify** | Reclassify 4 features, update summary table |

---

## Task 1: Create ApprovalGate

**Files:**
- Create: `herald-core/src/main/java/com/herald/agent/ApprovalGate.java`
- Test: `herald-core/src/test/java/com/herald/agent/ApprovalGateTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.herald.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ApprovalGateTest {

    @Test
    void approvedRequestReturnsApproved() throws Exception {
        MessageSender mockSender = mock(MessageSender.class);
        CountDownLatch messageSent = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); messageSent.countDown(); return null; })
                .when(mockSender).sendMessage(anyString());

        ApprovalGate gate = new ApprovalGate(Optional.of(mockSender), 5);

        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> gate.requestApproval("Run skill: weather"));

        assertThat(messageSent.await(5, TimeUnit.SECONDS)).isTrue();
        String approvalId = extractApprovalId(captured.get());
        gate.resolve(approvalId, true);

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("APPROVED");
    }

    @Test
    void deniedRequestReturnsDenied() throws Exception {
        MessageSender mockSender = mock(MessageSender.class);
        CountDownLatch messageSent = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); messageSent.countDown(); return null; })
                .when(mockSender).sendMessage(anyString());

        ApprovalGate gate = new ApprovalGate(Optional.of(mockSender), 5);

        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> gate.requestApproval("Run skill: broadcom"));

        assertThat(messageSent.await(5, TimeUnit.SECONDS)).isTrue();
        String approvalId = extractApprovalId(captured.get());
        gate.resolve(approvalId, false);

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("DENIED");
    }

    @Test
    void timeoutReturnTimeout() {
        MessageSender mockSender = mock(MessageSender.class);
        ApprovalGate gate = new ApprovalGate(Optional.of(mockSender), 1);

        String result = gate.requestApproval("Run skill: slow");

        assertThat(result).isEqualTo("TIMEOUT");
    }

    @Test
    void noSenderReturnsDenied() {
        ApprovalGate gate = new ApprovalGate(Optional.empty(), 5);

        String result = gate.requestApproval("Run skill: nope");

        assertThat(result).isEqualTo("DENIED");
    }

    @Test
    void resolveReturnsFalseForUnknownId() {
        ApprovalGate gate = new ApprovalGate(Optional.empty(), 5);

        assertThat(gate.resolve("nonexistent-id", true)).isFalse();
    }

    @Test
    void messageContainsConfirmInstructions() throws Exception {
        MessageSender mockSender = mock(MessageSender.class);
        CountDownLatch messageSent = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); messageSent.countDown(); return null; })
                .when(mockSender).sendMessage(anyString());

        ApprovalGate gate = new ApprovalGate(Optional.of(mockSender), 1);
        gate.requestApproval("Test action");

        assertThat(messageSent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get()).contains("/confirm");
        assertThat(captured.get()).contains("yes");
        assertThat(captured.get()).contains("no");
        assertThat(captured.get()).contains("Test action");
    }

    private static String extractApprovalId(String message) {
        return message.split("/confirm ")[1].split(" ")[0];
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl herald-core -Dtest=ApprovalGateTest -DfailIfNoTests=false`

Expected: Compilation error — `ApprovalGate` class does not exist.

- [ ] **Step 3: Implement ApprovalGate**

Create `herald-core/src/main/java/com/herald/agent/ApprovalGate.java`:

```java
package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGate.class);

    private final MessageSender messageSender;
    private final int timeoutSeconds;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();

    @Autowired
    public ApprovalGate(Optional<MessageSender> messageSender,
                        @Value("${herald.agent.approval-timeout-seconds:60}") int timeoutSeconds) {
        this.messageSender = messageSender.orElse(null);
        this.timeoutSeconds = timeoutSeconds;
    }

    public String requestApproval(String description) {
        if (messageSender == null) {
            log.warn("MessageSender not available; auto-denying approval for: {}", description);
            return "DENIED";
        }

        String approvalId = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingApprovals.put(approvalId, future);

        messageSender.sendMessage("Approval required:\n" + description + "\n"
                + "Reply: /confirm " + approvalId + " yes  OR  /confirm " + approvalId + " no");

        try {
            Boolean approved = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(approved)) {
                log.info("Approved: {}", description);
                return "APPROVED";
            }
            log.info("Denied: {}", description);
            return "DENIED";
        } catch (TimeoutException e) {
            log.warn("Approval timed out after {}s: {}", timeoutSeconds, description);
            return "TIMEOUT";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "DENIED";
        } catch (Exception e) {
            log.error("Error during approval for: {} — {}", description, e.getMessage());
            return "DENIED";
        } finally {
            pendingApprovals.remove(approvalId);
        }
    }

    public boolean resolve(String approvalId, boolean approved) {
        CompletableFuture<Boolean> future = pendingApprovals.get(approvalId);
        if (future != null) {
            future.complete(approved);
            return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl herald-core -Dtest=ApprovalGateTest`

Expected: All 6 tests PASS.

- [ ] **Step 5: Commit**

```
git add herald-core/src/main/java/com/herald/agent/ApprovalGate.java
git add herald-core/src/test/java/com/herald/agent/ApprovalGateTest.java
git commit -m "feat: add ApprovalGate — shared HITL approval abstraction"
```

---

## Task 2: Refactor HeraldShellDecorator to Use ApprovalGate

**Files:**
- Modify: `herald-persistence/src/main/java/com/herald/tools/HeraldShellDecorator.java`
- Modify: `herald-persistence/src/test/java/com/herald/tools/HeraldShellDecoratorTest.java`

- [ ] **Step 1: Update HeraldShellDecorator to delegate to ApprovalGate**

In `herald-persistence/src/main/java/com/herald/tools/HeraldShellDecorator.java`:

**Remove** these fields and methods:
- Field: `private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingConfirmations`
- Method: `public void confirmCommand(String confirmId, boolean approved)` (lines 117-122)
- Method: `private String handleConfirmation(String command)` (lines 124-162)

**Remove** unused imports: `UUID`, `CompletableFuture`, `ConcurrentHashMap`, `TimeUnit`, `TimeoutException`.

**Add** import: `import com.herald.agent.ApprovalGate;`

**Add** field:

```java
private final ApprovalGate approvalGate;
```

**Update** the main constructor to accept `ApprovalGate`:

```java
@Autowired
public HeraldShellDecorator(ShellSecurityConfig securityConfig,
                     Optional<ShellCommandExecutor> delegate,
                     Optional<MessageSender> messageSender,
                     JdbcTemplate jdbcTemplate,
                     ApprovalGate approvalGate) {
    this.securityConfig = securityConfig;
    this.jdbcTemplate = jdbcTemplate;
    this.blocklist = securityConfig.getShellBlocklist().stream()
            .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
            .toList();
    this.delegate = delegate.orElse(command ->
            executeCommandInternal(command, securityConfig.getShellTimeoutSeconds()));
    this.telegramSender = messageSender.orElse(null);
    this.approvalGate = approvalGate;
}
```

**Update** the package-private test constructor:

```java
HeraldShellDecorator(ShellSecurityConfig securityConfig) {
    this(securityConfig, Optional.empty(), Optional.empty(), null,
         new ApprovalGate(Optional.empty(), 60));
}
```

**Replace** the confirmation block inside `shell_exec()` (lines 76-79):

```java
if (requiresConfirmation(command)) {
    log.info("Shell command requires confirmation: [{}]", redactForLog(command));
    String approval = approvalGate.requestApproval("Shell command: " + redactForLog(command));
    if ("APPROVED".equals(approval)) {
        log.info("Command confirmed by user, executing: [{}]", redactForLog(command));
        String result = delegate.execute(command);
        log.info("Shell command completed: [{}]", redactForLog(command));
        return result;
    }
    if ("TIMEOUT".equals(approval)) {
        return "TIMEOUT: Confirmation timed out after "
                + securityConfig.getConfirmationTimeoutSeconds()
                + "s. Command was not executed: " + redactForLog(command);
    }
    return "DENIED: Command was rejected by user: " + redactForLog(command);
}
```

- [ ] **Step 2: Update HeraldShellDecoratorTest**

For each test that previously called `dec.confirmCommand(confirmId, ...)`, create an `ApprovalGate` and use `approvalGate.resolve(confirmId, ...)` instead.

**Pattern for each confirmation test:**

Old:
```java
HeraldShellDecorator dec = new HeraldShellDecorator(
        config, Optional.of(mockExecutor), Optional.of(mockSender), null);
// ... later:
dec.confirmCommand(confirmId, true);
```

New:
```java
ApprovalGate approvalGate = new ApprovalGate(Optional.of(mockSender), 5);
HeraldShellDecorator dec = new HeraldShellDecorator(
        config, Optional.of(mockExecutor), Optional.of(mockSender), null, approvalGate);
// ... later:
approvalGate.resolve(confirmId, true);
```

Add import: `import com.herald.agent.ApprovalGate;`

**Tests to update:**
- `confirmationApprovedExecutesCommand` — create `ApprovalGate`, replace `dec.confirmCommand` with `approvalGate.resolve`. Note: the captured message now comes from `ApprovalGate` (format: `"Approval required:\nShell command: ...\nReply: /confirm <uuid> ..."`). The `/confirm <uuid>` extraction pattern still works.
- `confirmationDeniedRejectsCommand` — same pattern, pass `false`.
- `deniedResponseRedactsSensitiveData` — same pattern, verify redacted content in result.
- `confirmationIdIsFullUuid` — same pattern, verify UUID format from `ApprovalGate` message.
- `confirmationTimeoutSendsTelegramAndTimesOut` — create `ApprovalGate` with timeout 1: `new ApprovalGate(Optional.of(mockSender), 1)`, pass to decorator constructor with 5 args.
- `telegramConfirmationMessageRedactsSensitiveData` — create `ApprovalGate` with sender, verify its message contains `[REDACTED]` and not the secret. Note the message sender receives TWO calls now (one from ApprovalGate), so use an `ArgumentCaptor` to capture all calls.
- `confirmationRequiredWithoutTelegramRedactsSensitiveContent` — the simple test constructor `new HeraldShellDecorator(config)` creates an `ApprovalGate` with no sender internally. `ApprovalGate` returns `"DENIED"`. Update assertion from `"CONFIRMATION REQUIRED"` to `"DENIED"`.

- [ ] **Step 3: Run tests to verify they pass**

Run: `./mvnw test -pl herald-persistence -Dtest=HeraldShellDecoratorTest`

Expected: All tests PASS.

- [ ] **Step 4: Commit**

```
git add herald-persistence/src/main/java/com/herald/tools/HeraldShellDecorator.java
git add herald-persistence/src/test/java/com/herald/tools/HeraldShellDecoratorTest.java
git commit -m "refactor: delegate shell confirmation to shared ApprovalGate"
```

---

## Task 3: Add /confirm Command to CommandHandler

**Files:**
- Modify: `herald-telegram/src/main/java/com/herald/telegram/CommandHandler.java`
- Modify: `herald-telegram/src/test/java/com/herald/telegram/CommandHandlerTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `CommandHandlerTest.java`:

Add import at top:
```java
import com.herald.agent.ApprovalGate;
```

Add field:
```java
private ApprovalGate approvalGate;
```

Update `setUp()` — after `reloadableSkillsTool = new ReloadableSkillsTool(tempDir.toString());` add:
```java
approvalGate = mock(ApprovalGate.class);
```

Update the CommandHandler constructor call to include approvalGate as the last arg:
```java
handler = new CommandHandler(cronService, chatMemory, sender, usageTracker, modelSwitcher,
        List.of("memory", "shell", "filesystem", "todo", "ask", "task", "taskOutput", "skills", "cron"),
        reloadableSkillsTool, 200_000, approvalGate);
```

Add these test methods:
```java
// --- /confirm ---

@Test
void confirmApprovedResolvesApproval() {
    when(approvalGate.resolve("abc-123", true)).thenReturn(true);
    assertThat(handler.handle("/confirm abc-123 yes")).isTrue();
    verify(approvalGate).resolve("abc-123", true);
}

@Test
void confirmDeniedResolvesApproval() {
    when(approvalGate.resolve("abc-123", false)).thenReturn(true);
    assertThat(handler.handle("/confirm abc-123 no")).isTrue();
    verify(approvalGate).resolve("abc-123", false);
}

@Test
void confirmUnknownIdSendsError() {
    when(approvalGate.resolve("unknown-id", true)).thenReturn(false);
    handler.handle("/confirm unknown-id yes");
    verify(sender).sendMessage(argThat(msg -> msg.contains("No pending approval")));
}

@Test
void confirmWithNoArgsShowsUsage() {
    handler.handle("/confirm");
    verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
}

@Test
void helpIncludesConfirmCommand() {
    handler.handle("/help");
    verify(sender).sendMessage(argThat(msg -> msg.contains("/confirm")));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl herald-telegram -Dtest=CommandHandlerTest`

Expected: Compilation error — `CommandHandler` constructor doesn't accept `ApprovalGate` yet.

- [ ] **Step 3: Implement /confirm in CommandHandler**

In `herald-telegram/src/main/java/com/herald/telegram/CommandHandler.java`:

Add import:
```java
import com.herald.agent.ApprovalGate;
```

Add field:
```java
private final ApprovalGate approvalGate;
```

Update constructor — add `ApprovalGate approvalGate` as the last parameter:
```java
public CommandHandler(CronService cronService, ChatMemory chatMemory,
                      TelegramSender sender, UsageTracker usageTracker, ModelSwitcher modelSwitcher,
                      @Qualifier("activeToolNames") List<String> activeToolNames,
                      ReloadableSkillsTool reloadableSkillsTool,
                      @Value("${herald.agent.max-context-tokens:200000}") int maxContextTokens,
                      ApprovalGate approvalGate) {
    this.cronService = cronService;
    this.chatMemory = chatMemory;
    this.sender = sender;
    this.usageTracker = usageTracker;
    this.modelSwitcher = modelSwitcher;
    this.reloadableSkillsTool = reloadableSkillsTool;
    this.activeToolsCount = activeToolNames.size();
    this.maxContextTokens = maxContextTokens;
    this.approvalGate = approvalGate;
}
```

Add case to the `handle()` switch statement, inside the `switch (command)` block:
```java
case "/confirm" -> handleConfirm(parts);
```

Add handler method:
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
}
```

Add `/confirm` to the help text string in `handleHelp()`, after the `/skills reload` line:
```
            /confirm <id> yes|no — Approve or deny a pending action
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl herald-telegram -Dtest=CommandHandlerTest`

Expected: All tests PASS.

- [ ] **Step 5: Commit**

```
git add herald-telegram/src/main/java/com/herald/telegram/CommandHandler.java
git add herald-telegram/src/test/java/com/herald/telegram/CommandHandlerTest.java
git commit -m "feat: add /confirm command for HITL approval via Telegram"
```

---

## Task 4: Wire HITL Approval into ReloadableSkillsTool

**Files:**
- Modify: `herald-core/src/main/java/com/herald/agent/ReloadableSkillsTool.java`
- Create: `herald-core/src/test/java/com/herald/agent/ReloadableSkillsToolApprovalTest.java`
- Modify: `herald-core/src/main/java/com/herald/config/HeraldConfig.java`
- Modify: `herald-bot/src/main/resources/application.yaml`

- [ ] **Step 1: Add config property to HeraldConfig**

In `herald-core/src/main/java/com/herald/config/HeraldConfig.java`:

Update the `Agent` record (line 42-44) to add `skillsRequiringApproval`:
```java
public record Agent(String persona, String systemPromptExtra, String contextFile,
                    Integer maxContextTokens, String defaultProvider,
                    List<String> anthropicSkills,
                    List<String> skillsRequiringApproval) {
}
```

Add convenience accessor after the `anthropicSkills()` method (around line 99):
```java
public List<String> skillsRequiringApproval() {
    if (agent != null && agent.skillsRequiringApproval() != null) {
        return agent.skillsRequiringApproval();
    }
    return List.of();
}
```

Add to `herald-bot/src/main/resources/application.yaml` under `herald.agent:` (after the `prompt-dump` line):
```yaml
    approval-timeout-seconds: ${HERALD_APPROVAL_TIMEOUT_SECONDS:60}
    skills-requiring-approval: ${HERALD_SKILLS_REQUIRING_APPROVAL:}
```

- [ ] **Step 2: Write the failing tests for ReloadableSkillsTool approval**

Create `herald-core/src/test/java/com/herald/agent/ReloadableSkillsToolApprovalTest.java`:

```java
package com.herald.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReloadableSkillsToolApprovalTest {

    @TempDir
    Path tempDir;

    private void writeSkill(String name, String frontmatter) throws IOException {
        Path skillDir = tempDir.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\n" + frontmatter + "\n---\n# " + name + "\nInstructions.");
    }

    @Test
    void skillWithRequiresApprovalFrontmatterIsBlocked() throws IOException {
        writeSkill("dangerous", "name: dangerous\ndescription: A dangerous skill\nrequires-approval: true");

        ApprovalGate gate = mock(ApprovalGate.class);
        when(gate.requestApproval(anyString())).thenReturn("DENIED");

        ReloadableSkillsTool tool = new ReloadableSkillsTool(
                tempDir.toString(), List.of(), gate, List.of());

        String result = tool.call("{\"command\":\"dangerous\"}");
        assertThat(result).contains("DENIED");
        verify(gate).requestApproval("Execute skill: dangerous");
    }

    @Test
    void skillInConfigListIsBlocked() throws IOException {
        writeSkill("safe-name", "name: safe-name\ndescription: A safe skill");

        ApprovalGate gate = mock(ApprovalGate.class);
        when(gate.requestApproval(anyString())).thenReturn("DENIED");

        ReloadableSkillsTool tool = new ReloadableSkillsTool(
                tempDir.toString(), List.of(), gate, List.of("safe-name"));

        String result = tool.call("{\"command\":\"safe-name\"}");
        assertThat(result).contains("DENIED");
        verify(gate).requestApproval("Execute skill: safe-name");
    }

    @Test
    void approvedSkillReturnsContent() throws IOException {
        writeSkill("approved-skill", "name: approved-skill\ndescription: Needs approval\nrequires-approval: true");

        ApprovalGate gate = mock(ApprovalGate.class);
        when(gate.requestApproval(anyString())).thenReturn("APPROVED");

        ReloadableSkillsTool tool = new ReloadableSkillsTool(
                tempDir.toString(), List.of(), gate, List.of());

        String result = tool.call("{\"command\":\"approved-skill\"}");
        assertThat(result).doesNotContain("DENIED");
        assertThat(result).contains("approved-skill");
    }

    @Test
    void skillWithoutApprovalSkipsGate() throws IOException {
        writeSkill("normal", "name: normal\ndescription: A normal skill");

        ApprovalGate gate = mock(ApprovalGate.class);

        ReloadableSkillsTool tool = new ReloadableSkillsTool(
                tempDir.toString(), List.of(), gate, List.of());

        tool.call("{\"command\":\"normal\"}");
        verifyNoInteractions(gate);
    }

    @Test
    void nullApprovalGateSkipsCheck() throws IOException {
        writeSkill("any-skill", "name: any-skill\ndescription: A skill\nrequires-approval: true");

        ReloadableSkillsTool tool = new ReloadableSkillsTool(
                tempDir.toString(), List.of(), null, List.of());

        String result = tool.call("{\"command\":\"any-skill\"}");
        assertThat(result).doesNotContain("DENIED");
    }

    @Test
    void requiresApprovalParsesStringTrue() throws IOException {
        writeSkill("stringy", "name: stringy\ndescription: Desc\nrequires-approval: true");

        ReloadableSkillsTool tool = new ReloadableSkillsTool(
                tempDir.toString(), List.of(), null, List.of());

        assertThat(tool.requiresApproval("stringy")).isTrue();
    }

    @Test
    void requiresApprovalReturnsFalseWhenAbsent() throws IOException {
        writeSkill("plain", "name: plain\ndescription: Desc");

        ReloadableSkillsTool tool = new ReloadableSkillsTool(
                tempDir.toString(), List.of(), null, List.of());

        assertThat(tool.requiresApproval("plain")).isFalse();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw test -pl herald-core -Dtest=ReloadableSkillsToolApprovalTest -DfailIfNoTests=false`

Expected: Compilation error — constructor doesn't accept `ApprovalGate` and `List<String>`.

- [ ] **Step 4: Implement approval in ReloadableSkillsTool**

In `herald-core/src/main/java/com/herald/agent/ReloadableSkillsTool.java`:

Add fields after the existing fields:
```java
private final ApprovalGate approvalGate;
private final List<String> skillsRequiringApproval;
```

Replace the existing constructors with a delegation chain:
```java
public ReloadableSkillsTool(String skillsDirectory) {
    this(skillsDirectory, List.of(), null, List.of());
}

public ReloadableSkillsTool(String skillsDirectory, List<Resource> classpathResources) {
    this(skillsDirectory, classpathResources, null, List.of());
}

public ReloadableSkillsTool(String skillsDirectory, List<Resource> classpathResources,
                            ApprovalGate approvalGate, List<String> skillsRequiringApproval) {
    if (skillsDirectory.startsWith("~")) {
        skillsDirectory = System.getProperty("user.home") + skillsDirectory.substring(1);
    }
    this.skillsDirectory = skillsDirectory;
    this.classpathResources = classpathResources != null ? List.copyOf(classpathResources) : List.of();
    this.approvalGate = approvalGate;
    this.skillsRequiringApproval = skillsRequiringApproval != null
            ? List.copyOf(skillsRequiringApproval) : List.of();
    reload();
}
```

Add `requiresApproval()` method after `getSkillModel()`:
```java
public boolean requiresApproval(String skillName) {
    if (skillsRequiringApproval.contains(skillName)) return true;
    if (currentSkills == null) return false;
    return currentSkills.stream()
            .filter(s -> s.name().equals(skillName))
            .findFirst()
            .map(s -> s.frontMatter().get("requires-approval"))
            .map(v -> Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v)))
            .orElse(false);
}
```

Update `call()` — insert approval check after skill name extraction, before `delegate.call()`:
```java
@Override
public String call(String toolInput) {
    if (delegate == null) {
        return "No skills are currently loaded.";
    }

    String skillName = extractSkillName(toolInput);

    if (skillName != null && requiresApproval(skillName) && approvalGate != null) {
        String approval = approvalGate.requestApproval("Execute skill: " + skillName);
        if (!"APPROVED".equals(approval)) {
            return "DENIED: Skill '" + skillName + "' requires user approval. Status: " + approval;
        }
    }

    String result = delegate.call(toolInput);

    if (skillName != null) {
        List<String> allowedTools = getAllowedTools(skillName);
        if (!allowedTools.isEmpty()) {
            result = "<allowed-tools>\nWhile executing this skill, you may ONLY use these tools: "
                    + String.join(", ", allowedTools)
                    + "\n</allowed-tools>\n\n" + result;
        }
    }
    return result;
}
```

Make `parseStringOrList` public (change access modifier):
```java
public static List<String> parseStringOrList(Object value) {
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -pl herald-core -Dtest=ReloadableSkillsToolApprovalTest`

Expected: All 7 tests PASS.

Also run all herald-core tests:

Run: `./mvnw test -pl herald-core`

Expected: All tests PASS.

- [ ] **Step 6: Commit**

```
git add herald-core/src/main/java/com/herald/agent/ReloadableSkillsTool.java
git add herald-core/src/test/java/com/herald/agent/ReloadableSkillsToolApprovalTest.java
git add herald-core/src/main/java/com/herald/config/HeraldConfig.java
git add herald-bot/src/main/resources/application.yaml
git commit -m "feat: add HITL approval gate for skill execution"
```

---

## Task 5: Create ValidateSkillTool

**Files:**
- Create: `herald-core/src/main/java/com/herald/agent/ValidateSkillTool.java`
- Create: `herald-core/src/test/java/com/herald/agent/ValidateSkillToolTest.java`

- [ ] **Step 1: Write the failing tests**

Create `herald-core/src/test/java/com/herald/agent/ValidateSkillToolTest.java`:

```java
package com.herald.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidateSkillToolTest {

    private final ValidateSkillTool tool = new ValidateSkillTool("/tmp/skills");

    @Test
    void validSkillReturnsOk() {
        String content = """
                ---
                name: my-skill
                description: A test skill
                ---
                # My Skill
                Do the thing.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).startsWith("OK");
        assertThat(result).contains("my-skill");
        assertThat(result).contains("/tmp/skills/my-skill/SKILL.md");
    }

    @Test
    void validSkillWithAllOptionalFields() {
        String content = """
                ---
                name: full-skill
                description: A fully specified skill
                allowed-tools: shell, web
                model: claude-sonnet-4-5
                requires-approval: true
                ---
                # Full Skill
                Instructions here.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).startsWith("OK");
    }

    @Test
    void missingFrontmatterDelimiter() {
        String content = "# Just Markdown\nNo frontmatter.";
        String result = tool.validateSkill(content);
        assertThat(result).contains("must start with");
    }

    @Test
    void missingClosingDelimiter() {
        String content = "---\nname: broken\n# No closing delimiter";
        String result = tool.validateSkill(content);
        assertThat(result).contains("closing");
    }

    @Test
    void missingNameField() {
        String content = """
                ---
                description: No name
                ---
                # Skill
                Body.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).contains("name");
    }

    @Test
    void invalidNameFormat() {
        String content = """
                ---
                name: My Skill With Spaces
                description: Invalid name
                ---
                # Skill
                Body.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).contains("name");
        assertThat(result).contains("lowercase");
    }

    @Test
    void missingDescription() {
        String content = """
                ---
                name: no-desc
                ---
                # Skill
                Body.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).contains("description");
    }

    @Test
    void emptyMarkdownBody() {
        String content = """
                ---
                name: empty-body
                description: Has no body
                ---
                """;
        String result = tool.validateSkill(content);
        assertThat(result).contains("body");
    }

    @Test
    void nullContentReturnsError() {
        String result = tool.validateSkill(null);
        assertThat(result).contains("empty");
    }

    @Test
    void blankContentReturnsError() {
        String result = tool.validateSkill("   ");
        assertThat(result).contains("empty");
    }

    @Test
    void invalidYamlReturnsError() {
        String content = """
                ---
                name: [invalid yaml
                description: broken
                ---
                # Skill
                Body.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).containsIgnoringCase("YAML");
    }

    @Test
    void allowedToolsAsListIsValid() {
        String content = """
                ---
                name: list-tools
                description: Uses list syntax
                allowed-tools:
                  - shell
                  - web
                ---
                # Skill
                Body.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).startsWith("OK");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl herald-core -Dtest=ValidateSkillToolTest -DfailIfNoTests=false`

Expected: Compilation error — `ValidateSkillTool` class does not exist.

- [ ] **Step 3: Implement ValidateSkillTool**

Create `herald-core/src/main/java/com/herald/agent/ValidateSkillTool.java`:

```java
package com.herald.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ValidateSkillTool {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    private final String skillsDirectory;

    public ValidateSkillTool(
            @Value("${herald.agent.skills-directory:skills}") String skillsDirectory) {
        if (skillsDirectory.startsWith("~")) {
            skillsDirectory = System.getProperty("user.home") + skillsDirectory.substring(1);
        }
        this.skillsDirectory = skillsDirectory;
    }

    @Tool(description = "Validate a SKILL.md file before writing it to the skills directory. "
            + "Pass the full content of the SKILL.md you intend to write. "
            + "Returns OK if valid, or a list of errors to fix.")
    public String validateSkill(
            @ToolParam(description = "The full content of the SKILL.md file to validate")
            String content) {

        if (content == null || content.isBlank()) {
            return "Validation failed:\n1. Content is empty. "
                    + "Provide SKILL.md content with YAML frontmatter and a Markdown body.";
        }

        List<String> errors = new ArrayList<>();
        String trimmed = content.strip();

        if (!trimmed.startsWith("---")) {
            errors.add("Content must start with '---' (YAML frontmatter delimiter).");
            return formatErrors(errors);
        }

        int closingIdx = trimmed.indexOf("---", 3);
        if (closingIdx < 0) {
            errors.add("Missing closing '---' delimiter for YAML frontmatter.");
            return formatErrors(errors);
        }

        String yamlBlock = trimmed.substring(3, closingIdx).strip();
        String markdownBody = trimmed.substring(closingIdx + 3).strip();

        Map<String, Object> frontmatter;
        try {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(yamlBlock);
            if (!(parsed instanceof Map)) {
                errors.add("YAML frontmatter must be a mapping (key: value pairs).");
                return formatErrors(errors);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fm = (Map<String, Object>) parsed;
            frontmatter = fm;
        } catch (Exception e) {
            errors.add("Invalid YAML in frontmatter: " + e.getMessage());
            return formatErrors(errors);
        }

        Object nameVal = frontmatter.get("name");
        if (nameVal == null || nameVal.toString().isBlank()) {
            errors.add("Missing required field: name");
        } else if (!NAME_PATTERN.matcher(nameVal.toString()).matches()) {
            errors.add("name must be lowercase letters, numbers, and hyphens only "
                    + "(e.g., 'my-skill'). Got: '" + nameVal + "'");
        }

        Object descVal = frontmatter.get("description");
        if (descVal == null || descVal.toString().isBlank()) {
            errors.add("Missing required field: description");
        }

        if (frontmatter.containsKey("allowed-tools")) {
            try {
                List<String> tools = ReloadableSkillsTool.parseStringOrList(
                        frontmatter.get("allowed-tools"));
                if (tools.isEmpty()) {
                    errors.add("allowed-tools is present but empty. "
                            + "Remove it or provide tool names.");
                }
            } catch (Exception e) {
                errors.add("allowed-tools could not be parsed: " + e.getMessage());
            }
        }

        if (frontmatter.containsKey("model")) {
            Object modelVal = frontmatter.get("model");
            if (modelVal == null || modelVal.toString().isBlank()) {
                errors.add("model field is present but empty.");
            }
        }

        if (frontmatter.containsKey("requires-approval")) {
            Object approvalVal = frontmatter.get("requires-approval");
            if (!(approvalVal instanceof Boolean)
                    && !"true".equalsIgnoreCase(String.valueOf(approvalVal))
                    && !"false".equalsIgnoreCase(String.valueOf(approvalVal))) {
                errors.add("requires-approval must be true or false. Got: '"
                        + approvalVal + "'");
            }
        }

        if (markdownBody.isEmpty()) {
            errors.add("Markdown body after frontmatter is empty. "
                    + "Add skill instructions.");
        }

        if (!errors.isEmpty()) {
            return formatErrors(errors);
        }

        String name = nameVal.toString();
        return "OK — skill '" + name + "' is valid and ready to write to: "
                + skillsDirectory + "/" + name + "/SKILL.md";
    }

    private static String formatErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder("Validation failed:\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append(i + 1).append(". ").append(errors.get(i)).append("\n");
        }
        return sb.toString().strip();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl herald-core -Dtest=ValidateSkillToolTest`

Expected: All 12 tests PASS.

- [ ] **Step 5: Commit**

```
git add herald-core/src/main/java/com/herald/agent/ValidateSkillTool.java
git add herald-core/src/test/java/com/herald/agent/ValidateSkillToolTest.java
git commit -m "feat: add ValidateSkillTool for agent-authored skill validation"
```

---

## Task 6: Add Self-Teaching System Prompt + Wire Beans

**Files:**
- Modify: `herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md`
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`

- [ ] **Step 1: Add self-teaching section to system prompt**

In `herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md`, add after the `# Safety Rules` section (after the line "When uncertain about a destructive or irreversible action, ask first.") and before `# Dan's Context`:

```markdown

# Self-Teaching — Creating New Skills

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

    ---
    name: skill-name
    description: >
      What this skill does and when to use it.
    allowed-tools: shell, web
    model: claude-sonnet-4-5
    requires-approval: false
    ---

    # Skill Title

    Markdown instructions for executing the skill...

**When to create a skill:**
- The user explicitly asks you to remember a workflow
- You've performed the same multi-step task more than once
- A complex API or CLI pattern would benefit from documented steps

**When NOT to create a skill:**
- One-off tasks unlikely to recur
- Simple commands that don't need documentation
- Tasks already covered by an existing skill
```

Note: The SKILL.md format example uses indented code block (4 spaces) instead of fenced code blocks to avoid nested triple-backtick issues in the prompt template.

- [ ] **Step 2: Update resolvePrompt() to handle {skills_directory} placeholder**

In `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`, update the `resolvePrompt()` method (around line 419):

Old:
```java
String resolvePrompt(String template, HeraldConfig config, String modelId) {
    return template
            .replace("{persona}", config.persona())
            .replace("{model_id}", modelId)
            .replace("{system_prompt_extra}", config.systemPromptExtra());
}
```

New:
```java
String resolvePrompt(String template, HeraldConfig config, String modelId, String skillsDirectory) {
    return template
            .replace("{persona}", config.persona())
            .replace("{model_id}", modelId)
            .replace("{system_prompt_extra}", config.systemPromptExtra())
            .replace("{skills_directory}", skillsDirectory);
}
```

Update the caller in the `modelSwitcher()` method. Find the line (around line 188):
```java
String systemPrompt = resolvePrompt(promptTemplate, config, defaultModel);
```

Replace with:
```java
String systemPrompt = resolvePrompt(promptTemplate, config, defaultModel,
        reloadableSkillsTool.getSkillsDirectory());
```

- [ ] **Step 3: Wire ValidateSkillTool into the tool list**

In `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`:

Add `ValidateSkillTool` parameter to `modelSwitcher()` method:
```java
@Bean
public ModelSwitcher modelSwitcher(
        @Qualifier("anthropicChatModel") ChatModel chatModel,
        HeraldConfig config,
        // ... existing params ...
        ReloadableSkillsTool reloadableSkillsTool,
        ValidateSkillTool validateSkillTool,
        // ... rest ...
```

Add `ValidateSkillTool` parameter to `buildToolList()`:
```java
List<Object> buildToolList(
        HeraldShellDecorator shellDecorator,
        FileSystemTools fsTools,
        org.springaicommunity.agent.tools.TodoWriteTool todoTool,
        AskUserQuestionTool askTool,
        Optional<TelegramSendTool> telegramSendToolOpt,
        Optional<GwsTools> gwsToolsOpt,
        WebTools webTools,
        Optional<CronTools> cronToolsOpt,
        ValidateSkillTool validateSkillTool) {

    List<Object> tools = new ArrayList<>();
    tools.add(shellDecorator);
    tools.add(fsTools);
    tools.add(todoTool);
    tools.add(askTool);
    tools.add(webTools);
    tools.add(validateSkillTool);
    // ... rest unchanged ...
```

Update the `buildToolList()` call site to pass `validateSkillTool`:
```java
var toolList = buildToolList(shellDecorator, fsTools,
        todoTool, askTool, telegramSendToolOpt, gwsToolsOpt, webTools, cronToolsOpt,
        validateSkillTool);
```

Add `"validateSkill"` to `activeToolNames`:
```java
List<String> names = new ArrayList<>(List.of(
        "shell", "filesystem", "todoWrite", "askUserQuestion",
        "task", "taskOutput", "skills", "web", "toolSearchTool",
        "validateSkill",
        "MemoryView", "MemoryCreate", "MemoryStrReplace",
        "MemoryInsert", "MemoryDelete", "MemoryRename"));
```

- [ ] **Step 4: Wire ApprovalGate + config into ReloadableSkillsTool bean**

In `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`:

Update the `reloadableSkillsTool` bean to accept `ApprovalGate` and `HeraldConfig`:

```java
@Bean
public ReloadableSkillsTool reloadableSkillsTool(
        @Value("${herald.agent.skills-directory:skills}") String skillsDirectory,
        ResourcePatternResolver resourceResolver,
        ApprovalGate approvalGate,
        HeraldConfig config) throws IOException {
    Resource[] skillMdFiles = resourceResolver.getResources("classpath:skills/*/SKILL.md");
    List<Resource> classpathSkillDirs = new ArrayList<>();
    for (Resource r : skillMdFiles) {
        try {
            classpathSkillDirs.add(new FileSystemResource(r.getFile().getParentFile()));
        } catch (IOException e) {
            log.warn("Could not resolve classpath skill resource to filesystem: {}", r, e);
        }
    }
    return new ReloadableSkillsTool(skillsDirectory, classpathSkillDirs,
            approvalGate, config.skillsRequiringApproval());
}
```

- [ ] **Step 5: Run the full test suite**

Run: `./mvnw test`

Expected: All tests PASS across all modules. If `CommandHandlerTest.statusShowsUptimeModelAndToolCount` fails due to tool count change (was 9, now 10 with `validateSkill`), update the assertion.

- [ ] **Step 6: Commit**

```
git add herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git add herald-bot/src/main/resources/application.yaml
git commit -m "feat: add self-teaching system prompt + wire ValidateSkillTool and ApprovalGate"
```

---

## Task 7: Update Documentation

**Files:**
- Modify: `docs/herald-patterns-comparison.md`

- [ ] **Step 1: Update Classpath skill loading entry**

In `docs/herald-patterns-comparison.md`, find the "Classpath / JAR packaging support" section and replace the Herald paragraph:

Old:
```
**Herald:** ➖ **Not Fully Implemented.** Herald loads skills from the filesystem only (`skills/`). Classpath loading is not currently implemented. Since Herald runs locally on macOS, this is a lower-priority gap.
```

New:
```
**Herald:** ✅ **Implemented.** `ReloadableSkillsTool` accepts both filesystem and classpath `Resource` sources. `HeraldAgentConfig` scans `classpath:skills/*/SKILL.md` at startup via `ResourcePatternResolver`. The built-in `system-info` skill at `herald-core/src/main/resources/skills/system-info/SKILL.md` demonstrates classpath packaging.
```

- [ ] **Step 2: Update Anthropic Native Skills entry**

Old:
```
**Herald:** ➖ **Not Fully Implemented.** Herald uses Generic Agent Skills only. Anthropic's native Skills API (Excel/PowerPoint/Word generation in sandboxed containers) is not yet wired in.
```

New:
```
**Herald:** ✅ **Implemented.** `HeraldConfig.anthropicSkills()` accepts a list of Anthropic skill IDs (e.g., `computer_use`, `code_execution`). `ModelSwitcher` applies them via `AnthropicChatOptions.builder().skill()` to the main agent. Subagents intentionally exclude native skills. Configure via `herald.agent.anthropic-skills` property or `HERALD_AGENT_ANTHROPIC_SKILLS` env var.
```

- [ ] **Step 3: Update Self-Teaching entry**

Old:
```
**Herald:** ➖ **Not Fully Implemented.** Since the agent has file system access and `ReloadableSkillsTool` instantly hot-reloads on file changes, the agent can be instructed to write its own `SKILL.md` files to the `skills/` directory (e.g., "save that workflow as a skill"). This allows the assistant to permanently "learn" new capabilities without human coding.
```

New:
```
**Herald:** ➕ **Enhanced.** The agent can author its own skills via `FileSystemTools`. A `ValidateSkillTool` checks SKILL.md content (frontmatter schema, naming conventions) before writing. The system prompt teaches the validate-then-write workflow and when to create skills. `SkillsWatcher` hot-reloads new skills within 250ms — no restart needed. This goes beyond the blog, which treats skills as human-authored only.
```

- [ ] **Step 4: Update HITL entry**

Old:
```
**Herald:** ➖ **Not Fully Implemented.** Introduce a `requires_approval: true` frontmatter field for skills. A `ToolCallback` wrapper will intercept the skill invocation, use the `TelegramQuestionHandler` to push an interactive "Approve / Deny" button to the user's Telegram, and block execution until explicitly approved.
```

New:
```
**Herald:** ➕ **Enhanced.** A shared `ApprovalGate` component provides UUID-keyed `CompletableFuture` approval via Telegram. Skills opt in via `requires-approval: true` frontmatter or the `herald.agent.skills-requiring-approval` config list. `ReloadableSkillsTool` checks both sources before execution and blocks on Telegram confirmation. The same gate also powers shell command confirmations (refactored from `HeraldShellDecorator`). The `/confirm <id> yes|no` Telegram command resolves both skill and shell approvals. This directly addresses the blog's stated "No Human-in-the-Loop" limitation.
```

- [ ] **Step 5: Update the summary table**

Find the Part 1 row in the summary table and replace:

Old:
```
| Part 1: Agent Skills | 7 | 3 (format, discovery, matching) | 2 (execution w/ guardrails, hot reload) | 4 (classpath, native Skills, self-teaching, HITL) |
```

New:
```
| Part 1: Agent Skills | 7 | 5 (format, discovery, matching, classpath, native skills) | 4 (execution w/ guardrails, hot reload, self-teaching, HITL) | 0 |
```

Also update the document header line that says "five patterns" to "six patterns" if not already updated.

- [ ] **Step 6: Commit**

```
git add docs/herald-patterns-comparison.md
git commit -m "docs: reclassify Part 1 features — all items now implemented or enhanced"
```

---

## Task 8: Final Verification

- [ ] **Step 1: Run the full test suite one final time**

Run: `./mvnw clean test`

Expected: All tests PASS across all modules. Zero compilation errors.

- [ ] **Step 2: Verify the application compiles cleanly**

Run: `./mvnw clean package -DskipTests`

Expected: BUILD SUCCESS. All modules package without errors.

- [ ] **Step 3: Commit any remaining changes**

If there are uncommitted fixes from verification:

```
git add -A
git commit -m "fix: address issues found during final verification"
```
