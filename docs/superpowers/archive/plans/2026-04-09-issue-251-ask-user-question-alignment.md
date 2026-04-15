# Issue #251: AskUserQuestion Library Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align Herald's `TelegramQuestionHandler` with spring-ai-agent-utils 0.7.0 by removing the duplicate internal `Question` record and configuring `InvalidUserAnswerException` propagation.

**Architecture:** Two discrete changes. (1) Add `spring.ai.tools.throw-exception-on-error` to `application.yaml` so Spring AI surfaces validation failures to users instead of the model. (2) Refactor `TelegramQuestionHandler` to operate directly on `AskUserQuestionTool.Question` throughout, deleting its internal `Question` record and converting tests accordingly.

**Tech Stack:** Java 21, Spring Boot, spring-ai-agent-utils 0.7.0, JUnit 5, Mockito, AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-04-09-issue-251-ask-user-question-alignment.md`

**Files touched (summary):**
- Modify: `herald-bot/src/main/resources/application.yaml` — add tool exception config
- Modify: `herald-telegram/src/main/java/com/herald/telegram/TelegramQuestionHandler.java` — delete internal `Question` record, refactor to upstream type
- Modify: `herald-telegram/src/test/java/com/herald/telegram/TelegramQuestionHandlerTest.java` — update tests to use upstream `Question`

---

## Task 1: Add exception propagation config to application.yaml

**Files:**
- Modify: `herald-bot/src/main/resources/application.yaml`

- [ ] **Step 1: Add the `tools` section under `spring.ai`**

Open `herald-bot/src/main/resources/application.yaml`. Find the existing `spring.ai` block (around line 11). Add a `tools` subsection immediately under `spring.ai`, before `anthropic`:

```yaml
  ai:
    tools:
      throw-exception-on-error: org.springaicommunity.agent.tools.AskUserQuestionTool$InvalidUserAnswerException
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}
```

The full edit target (use Edit tool) replaces:

```yaml
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}
```

with:

```yaml
  ai:
    tools:
      throw-exception-on-error: org.springaicommunity.agent.tools.AskUserQuestionTool$InvalidUserAnswerException
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}
```

- [ ] **Step 2: Verify the bot module still starts / config parses**

Run: `./mvnw -pl herald-bot -am test-compile -q`
Expected: build succeeds. (YAML is validated at runtime but compile ensures no unrelated regressions.)

- [ ] **Step 3: Commit**

```bash
git add herald-bot/src/main/resources/application.yaml
git commit -m "feat: propagate AskUserQuestion InvalidUserAnswerException to user

Configures spring.ai.tools.throw-exception-on-error so Spring AI
surfaces AskUserQuestionTool validation failures to the user rather
than feeding them back to the model. Per spring-ai-agent-utils 0.7.0
library recommendation.

Refs #251"
```

---

## Task 2: Refactor TelegramQuestionHandler to use upstream Question type

**Files:**
- Modify: `herald-telegram/src/main/java/com/herald/telegram/TelegramQuestionHandler.java`
- Modify: `herald-telegram/src/test/java/com/herald/telegram/TelegramQuestionHandlerTest.java`

This is a single logical change — the production class and its test move together because deleting the internal `Question` record breaks every test that constructs one. We write the tests first (RED), then refactor production code (GREEN), then commit once.

### Step 2.1: Rewrite TelegramQuestionHandlerTest.java

- [ ] **Step 2.1.1: Replace the test file in full**

The existing test imports `com.herald.telegram.TelegramQuestionHandler.Question` and constructs it everywhere. Every test that uses options needs to switch to `AskUserQuestionTool.Question` with its 4-arg constructor `(question, header, options, multiSelect)` where options are `List<AskUserQuestionTool.Question.Option>`.

Use Write tool to replace `herald-telegram/src/test/java/com/herald/telegram/TelegramQuestionHandlerTest.java` with:

```java
package com.herald.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question.Option;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TelegramQuestionHandlerTest {

    private TelegramSender sender;
    private TelegramQuestionHandler handler;

    @BeforeEach
    void setUp() {
        sender = mock(TelegramSender.class);
        handler = new TelegramQuestionHandler(sender);
    }

    private static Question freeText(String text) {
        return new Question(text, text, List.of(), false);
    }

    private static Question singleSelect(String text, String... labels) {
        List<Option> opts = java.util.Arrays.stream(labels)
                .map(l -> new Option(l, ""))
                .toList();
        return new Question(text, text, opts, false);
    }

    private static Question multiSelect(String text, String... labels) {
        List<Option> opts = java.util.Arrays.stream(labels)
                .map(l -> new Option(l, ""))
                .toList();
        return new Question(text, text, opts, true);
    }

    @Test
    void askQuestionSendsFormattedMessageAndReturnsAnswer() {
        CountDownLatch messageSent = new CountDownLatch(1);
        doAnswer(invocation -> {
            messageSent.countDown();
            return null;
        }).when(sender).sendMessage(anyString());

        CompletableFuture.runAsync(() -> {
            try {
                messageSent.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            handler.resolveAnswer("42");
        });

        String answer = handler.askQuestion("What is the meaning of life?");

        assertThat(answer).isEqualTo("42");
        verify(sender).sendMessage(anyString());
    }

    @Test
    void handleInternalReturnsEmptyMapForNullQuestions() {
        Map<String, String> result = handler.handleInternal(null);
        assertThat(result).isEmpty();
    }

    @Test
    void handleInternalReturnsEmptyMapForEmptyQuestions() {
        Map<String, String> result = handler.handleInternal(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void resolveAnswerReturnsFalseWhenNoPending() {
        assertThat(handler.resolveAnswer("some answer")).isFalse();
    }

    @Test
    void hasPendingQuestionReturnsFalseInitially() {
        assertThat(handler.hasPendingQuestion()).isFalse();
    }

    @Test
    void handleMultipleQuestionsFormatsCorrectly() {
        CountDownLatch messageSent = new CountDownLatch(1);
        doAnswer(invocation -> {
            messageSent.countDown();
            return null;
        }).when(sender).sendMessage(anyString());

        CompletableFuture.runAsync(() -> {
            try {
                messageSent.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            handler.resolveAnswer("A");
        });

        List<Question> questions = List.of(
                singleSelect("Which calendar?", "Work", "Personal", "Family"),
                freeText("What time?")
        );

        Map<String, String> result = handler.handleInternal(questions);

        assertThat(result).hasSize(2);
        assertThat(result).containsKeys("Which calendar?", "What time?");
        assertThat(result.get("Which calendar?")).isEqualTo("A");
    }

    @Test
    void formatQuestionsIncludesOptionsAndSelectionHint() {
        List<Question> questions = List.of(
                singleSelect("Which calendar?", "Work", "Personal", "Family")
        );

        String formatted = handler.formatQuestions("abc123", questions);

        assertThat(formatted).contains("Question from Herald [abc123]:");
        assertThat(formatted).contains("Which calendar?");
        assertThat(formatted).contains("A) Work");
        assertThat(formatted).contains("B) Personal");
        assertThat(formatted).contains("C) Family");
        assertThat(formatted).contains("Select one");
        assertThat(formatted).contains("Reply with your answer.");
    }

    @Test
    void formatQuestionsMultiSelectShowsHint() {
        List<Question> questions = List.of(
                multiSelect("Select tags:", "urgent", "work", "home")
        );

        String formatted = handler.formatQuestions("abc123", questions);

        assertThat(formatted).contains("Select multiple");
    }

    @Test
    void formatQuestionsNumbersMultipleQuestions() {
        List<Question> questions = List.of(
                freeText("First question?"),
                freeText("Second question?")
        );

        String formatted = handler.formatQuestions("abc123", questions);

        assertThat(formatted).contains("1. First question?");
        assertThat(formatted).contains("2. Second question?");
    }

    @Test
    void formatQuestionsSingleQuestionNotNumbered() {
        List<Question> questions = List.of(
                freeText("Only question?")
        );

        String formatted = handler.formatQuestions("abc123", questions);

        assertThat(formatted).doesNotContain("1.");
        assertThat(formatted).contains("Only question?");
    }

    @Test
    void pendingQuestionStateIsManagedCorrectly() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch messageSent = new CountDownLatch(1);
        doAnswer(invocation -> {
            messageSent.countDown();
            return null;
        }).when(sender).sendMessage(anyString());

        try {
            CompletableFuture<String> askFuture = CompletableFuture.supplyAsync(
                    () -> handler.askQuestion("test?"), executor);

            assertThat(messageSent.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(handler.hasPendingQuestion()).isTrue();

            handler.resolveAnswer("yes");

            String result = askFuture.get(5, TimeUnit.SECONDS);
            assertThat(result).isEqualTo("yes");

            assertThat(handler.hasPendingQuestion()).isFalse();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void handleRejectsSecondConcurrentQuestion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch messageSent = new CountDownLatch(1);
        doAnswer(invocation -> {
            messageSent.countDown();
            return null;
        }).when(sender).sendMessage(anyString());

        try {
            CompletableFuture<String> firstQuestion = CompletableFuture.supplyAsync(
                    () -> handler.askQuestion("first?"), executor);

            assertThat(messageSent.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> handler.handleInternal(List.of(freeText("second?"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already pending");

            handler.resolveAnswer("done");
            firstQuestion.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void handleReturnsEmptyMapOnTimeout() {
        TelegramQuestionHandler shortTimeoutHandler = new TelegramQuestionHandler(sender, 0);

        Map<String, String> result = shortTimeoutHandler.handleInternal(
                List.of(freeText("Will this timeout?")));

        assertThat(result).isEmpty();
        verify(sender).sendMessage(anyString());
    }

    @Test
    void handleUpstreamQuestionsConvertsAndDelegates() {
        CountDownLatch messageSent = new CountDownLatch(1);
        doAnswer(invocation -> {
            messageSent.countDown();
            return null;
        }).when(sender).sendMessageWithKeyboard(anyString(), anyList());

        CompletableFuture.runAsync(() -> {
            try {
                messageSent.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            handler.resolveAnswer("React");
        });

        var upstreamQuestion = new AskUserQuestionTool.Question(
                "Which framework do you prefer?",
                "Framework",
                List.of(new AskUserQuestionTool.Question.Option("React", "Popular JS framework"),
                        new AskUserQuestionTool.Question.Option("Vue", "Progressive framework")),
                false);

        AskUserQuestionTool.QuestionHandler qh = handler;
        Map<String, String> result = qh.handle(List.of(upstreamQuestion));

        assertThat(result).containsKey("Which framework do you prefer?");
        assertThat(result.get("Which framework do you prefer?")).isEqualTo("React");
        verify(sender).sendMessageWithKeyboard(anyString(), eq(List.of("React", "Vue")));
    }

    @Test
    void handleUpstreamFreeTextQuestionUsesPlainMessage() {
        CountDownLatch messageSent = new CountDownLatch(1);
        doAnswer(invocation -> {
            messageSent.countDown();
            return null;
        }).when(sender).sendMessage(anyString());

        CompletableFuture.runAsync(() -> {
            try {
                messageSent.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            handler.resolveAnswer("Next Tuesday");
        });

        var upstreamQuestion = new AskUserQuestionTool.Question(
                "When should we schedule it?",
                "Schedule",
                List.of(),
                false);

        AskUserQuestionTool.QuestionHandler qh = handler;
        Map<String, String> result = qh.handle(List.of(upstreamQuestion));

        assertThat(result).containsKey("When should we schedule it?");
        assertThat(result.get("When should we schedule it?")).isEqualTo("Next Tuesday");
        verify(sender).sendMessage(anyString());
        verify(sender, never()).sendMessageWithKeyboard(anyString(), anyList());
    }

    @Test
    void handleUpstreamMultiSelectFallsBackToTextMessage() {
        CountDownLatch messageSent = new CountDownLatch(1);
        doAnswer(invocation -> {
            messageSent.countDown();
            return null;
        }).when(sender).sendMessage(anyString());

        CompletableFuture.runAsync(() -> {
            try {
                messageSent.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            handler.resolveAnswer("React, Vue");
        });

        var upstreamQuestion = new AskUserQuestionTool.Question(
                "Select frameworks:",
                "Frameworks",
                List.of(new AskUserQuestionTool.Question.Option("React", "JS framework"),
                        new AskUserQuestionTool.Question.Option("Vue", "Progressive"),
                        new AskUserQuestionTool.Question.Option("Angular", "Full framework")),
                true);

        AskUserQuestionTool.QuestionHandler qh = handler;
        Map<String, String> result = qh.handle(List.of(upstreamQuestion));

        assertThat(result).containsKey("Select frameworks:");
        verify(sender).sendMessage(anyString());
        verify(sender, never()).sendMessageWithKeyboard(anyString(), anyList());
    }

    @Test
    void handleMultipleUpstreamQuestionsUsesTextNotKeyboard() {
        CountDownLatch messageSent = new CountDownLatch(1);
        doAnswer(invocation -> {
            messageSent.countDown();
            return null;
        }).when(sender).sendMessage(anyString());

        CompletableFuture.runAsync(() -> {
            try {
                messageSent.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            handler.resolveAnswer("React");
        });

        var q1 = new AskUserQuestionTool.Question(
                "Which framework?",
                "Framework",
                List.of(new AskUserQuestionTool.Question.Option("React", "JS"),
                        new AskUserQuestionTool.Question.Option("Vue", "Progressive")),
                false);
        var q2 = new AskUserQuestionTool.Question(
                "Which database?",
                "Database",
                List.of(new AskUserQuestionTool.Question.Option("Postgres", "Relational"),
                        new AskUserQuestionTool.Question.Option("Mongo", "Document")),
                false);

        AskUserQuestionTool.QuestionHandler qh = handler;
        Map<String, String> result = qh.handle(List.of(q1, q2));

        assertThat(result).hasSize(2);
        verify(sender).sendMessage(anyString());
        verify(sender, never()).sendMessageWithKeyboard(anyString(), anyList());
    }
}
```

Key changes from the old test file:
- Removed `import com.herald.telegram.TelegramQuestionHandler.Question;`
- Added `import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;` and `.Question.Option`
- Added three private helpers: `freeText`, `singleSelect`, `multiSelect` — reduce test boilerplate
- `handleMultipleQuestionsFormatsCorrectly`: keys changed from `q1`/`q2` to the question texts `"Which calendar?"`/`"What time?"`
- **Deleted** `handleWithCustomQuestionIdsUsesThemAsKeys` — upstream `Question` has no separate ID field, so this test's premise no longer exists. Keying-by-question-text is already covered by `handleMultipleQuestionsFormatsCorrectly` and the three `handleUpstream*` tests.

- [ ] **Step 2.1.2: Run tests to confirm they fail (compile error)**

Run: `./mvnw -pl herald-telegram test -Dtest=TelegramQuestionHandlerTest -q`
Expected: FAIL — compile errors because `TelegramQuestionHandler.handleInternal(List<Question>)` still takes the internal `Question` type, and helper methods like `formatQuestions` still take the internal type. Other test compile errors are expected too. This is the RED state.

### Step 2.2: Rewrite TelegramQuestionHandler.java

- [ ] **Step 2.2.1: Replace the production file in full**

Use Write tool to replace `herald-telegram/src/main/java/com/herald/telegram/TelegramQuestionHandler.java` with:

```java
package com.herald.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges agent questions with Telegram messaging.
 * Implements the upstream {@link AskUserQuestionTool.QuestionHandler} interface,
 * converting structured questions to Telegram messages with inline keyboard buttons
 * for single-select options, and text-based formatting for multi-select and free-text.
 *
 * <p>Operates directly on {@link AskUserQuestionTool.Question} throughout — no
 * internal question type. Answer-map keys are question texts, matching what
 * upstream validation expects.
 */
@Component
@ConditionalOnProperty("herald.telegram.bot-token")
public class TelegramQuestionHandler implements AskUserQuestionTool.QuestionHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramQuestionHandler.class);
    public static final long DEFAULT_TIMEOUT_MINUTES = 5;

    private final TelegramSender sender;
    private final long timeoutMinutes;
    private final AtomicReference<PendingQuestion> pendingQuestion = new AtomicReference<>();

    @Autowired
    public TelegramQuestionHandler(TelegramSender sender) {
        this(sender, DEFAULT_TIMEOUT_MINUTES);
    }

    TelegramQuestionHandler(TelegramSender sender, long timeoutMinutes) {
        this.sender = sender;
        this.timeoutMinutes = timeoutMinutes;
    }

    record PendingQuestion(String questionId, CompletableFuture<String> future) {
    }

    /**
     * Send a single free-text question to the user via Telegram and block until they reply.
     * Returns the user's answer, or an empty string on timeout.
     */
    public String askQuestion(String question) {
        Question q = new Question(question, question, List.of(), false);
        Map<String, String> results = handleInternal(List.of(q));
        return results.values().stream().findFirst().orElse("");
    }

    /**
     * Implements the upstream QuestionHandler interface.
     * Sends questions via Telegram (using inline keyboard for a single single-select
     * question with options, text for everything else) and returns answers keyed by
     * question text (as expected by upstream validation).
     */
    @Override
    public Map<String, String> handle(List<Question> questions) {
        if (questions == null || questions.isEmpty()) {
            return Map.of();
        }

        // Use inline keyboard only for a single single-select question with options
        if (questions.size() == 1 && isSingleSelectWithOptions(questions.getFirst())) {
            return handleWithKeyboard(questions.getFirst());
        }
        return handleInternal(questions);
    }

    /**
     * Send questions to the user via Telegram (text-based) and block until they reply.
     * Returns a map of question text to answer. On timeout, returns an empty map.
     *
     * <p>Only one question batch may be pending at a time. If a batch is already
     * pending, this method throws {@link IllegalStateException}.</p>
     *
     * <p><b>Limitation:</b> When multiple questions are sent in a single batch,
     * the user provides a single reply that is assigned to all question keys.
     * For independent answers per question, callers should send one question at a time.</p>
     */
    public Map<String, String> handleInternal(List<Question> questions) {
        if (questions == null || questions.isEmpty()) {
            return Map.of();
        }

        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String formatted = formatQuestions(batchId, questions);
        Optional<String> reply = sendAndAwaitReply(() -> sender.sendMessage(formatted));

        return reply.map(r -> buildAnswerMap(questions, r)).orElse(Map.of());
    }

    /**
     * Send a single-select question with inline keyboard buttons and block until reply.
     */
    private Map<String, String> handleWithKeyboard(Question question) {
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String messageText = "Question from Herald [" + batchId + "]:\n\n" + question.question();
        List<String> optionLabels = question.options().stream()
                .map(Question.Option::label)
                .toList();
        Optional<String> reply = sendAndAwaitReply(
                () -> sender.sendMessageWithKeyboard(messageText, optionLabels));

        return reply.map(r -> Map.of(question.question(), r)).orElse(Map.of());
    }

    /**
     * Core blocking method: registers a pending question, sends via the provided action,
     * blocks until a reply or timeout, and returns the raw reply string (or empty on timeout).
     */
    private Optional<String> sendAndAwaitReply(Runnable sendAction) {
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<String> future = new CompletableFuture<>();
        PendingQuestion pending = new PendingQuestion(batchId, future);

        if (!pendingQuestion.compareAndSet(null, pending)) {
            throw new IllegalStateException(
                    "A question is already pending. Only one question batch may be active at a time.");
        }

        sendAction.run();

        try {
            String reply = future.get(timeoutMinutes, TimeUnit.MINUTES);
            log.info("Received answer for question batch {}", batchId);
            return Optional.of(reply);
        } catch (TimeoutException e) {
            log.warn("Question batch {} timed out after {} minutes", batchId, timeoutMinutes);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Question batch {} was interrupted", batchId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error waiting for answer to question batch {}: {}", batchId, e.getMessage());
            return Optional.empty();
        } finally {
            pendingQuestion.set(null);
        }
    }

    /**
     * Called by TelegramPoller when the user replies to a pending question.
     * Returns true if the reply was matched to a pending question.
     */
    public boolean resolveAnswer(String answer) {
        PendingQuestion pending = pendingQuestion.get();
        if (pending == null) {
            return false;
        }
        pending.future().complete(answer);
        return true;
    }

    /**
     * Returns true if there is a pending question awaiting a user reply.
     */
    public boolean hasPendingQuestion() {
        return pendingQuestion.get() != null;
    }

    String formatQuestions(String batchId, List<Question> questions) {
        StringBuilder sb = new StringBuilder();
        sb.append("Question from Herald [").append(batchId).append("]:\n\n");

        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            if (questions.size() > 1) {
                sb.append(i + 1).append(". ");
            }
            sb.append(q.question()).append("\n");

            List<String> optionLabels = q.options() != null
                    ? q.options().stream().map(Question.Option::label).toList()
                    : List.of();

            if (!optionLabels.isEmpty()) {
                for (int j = 0; j < optionLabels.size(); j++) {
                    sb.append("  ").append((char) ('A' + j)).append(") ").append(optionLabels.get(j)).append("\n");
                }
                if (Boolean.TRUE.equals(q.multiSelect())) {
                    sb.append("(Select multiple: e.g. A, C)\n");
                } else {
                    sb.append("(Select one: e.g. A)\n");
                }
            }
            sb.append("\n");
        }

        sb.append("Reply with your answer.");
        return sb.toString();
    }

    private Map<String, String> buildAnswerMap(List<Question> questions, String reply) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (Question q : questions) {
            answers.put(q.question(), reply);
        }
        return answers;
    }

    private static boolean isSingleSelectWithOptions(Question q) {
        return q.options() != null
                && !q.options().isEmpty()
                && !Boolean.TRUE.equals(q.multiSelect());
    }
}
```

Summary of changes vs. old file:
- Deleted the nested `Question` record and its `SelectionType` enum entirely.
- Added `import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;`
- Removed `import java.util.ArrayList;` (no longer needed — conversion loop gone).
- `askQuestion(String)` now builds an upstream `Question` directly; deleted `askQuestion(Question)` overload.
- `handle(List<Question>)` dropped the upstream→internal conversion loop. The keyboard decision is now a one-liner helper `isSingleSelectWithOptions`.
- `handleInternal`, `handleWithKeyboard`, `formatQuestions`, `buildAnswerMap` all operate on `AskUserQuestionTool.Question`. Keys are `q.question()`.
- `handleWithKeyboard` derives option labels from `q.options().stream().map(Question.Option::label).toList()`.
- `formatQuestions` derives option labels and selection hint inline.
- `buildAnswerMap` keys entries by `q.question()` instead of positional `q1`/`q2`.
- New private helper `isSingleSelectWithOptions(Question)` replaces the old keyboard-decision logic.

- [ ] **Step 2.2.2: Run the full herald-telegram test suite**

Run: `./mvnw -pl herald-telegram test -q`
Expected: PASS — all tests in `TelegramQuestionHandlerTest` green, `TelegramPollerTest` unaffected.

- [ ] **Step 2.2.3: Run the herald-bot test suite to confirm wiring still works**

Run: `./mvnw -pl herald-bot -am test -q`
Expected: PASS — `HeraldAgentConfig` still compiles against `TelegramQuestionHandler` (it only uses the upstream `QuestionHandler` interface, not the deleted `Question` record).

- [ ] **Step 2.2.4: Commit**

```bash
git add herald-telegram/src/main/java/com/herald/telegram/TelegramQuestionHandler.java \
        herald-telegram/src/test/java/com/herald/telegram/TelegramQuestionHandlerTest.java
git commit -m "refactor: use upstream Question type in TelegramQuestionHandler

Deletes the internal Question record that duplicated
AskUserQuestionTool.Question from spring-ai-agent-utils. The class now
operates directly on the upstream type throughout, eliminating the
upstream->internal conversion loop and drift risk.

Answer-map keys are now question texts (q.question()) for all paths,
matching what upstream validation expects and unifying the two code
paths that previously differed on key format.

Refs #251"
```

---

## Task 3: Full build verification

**Files:** none (verification only)

- [ ] **Step 3.1: Run the full multi-module build**

Run: `./mvnw -q -DskipTests=false verify`
Expected: BUILD SUCCESS across all modules.

If any non-telegram/non-bot module fails with an unrelated error, note it but do not attempt to fix in this plan.

- [ ] **Step 3.2: Confirm the new YAML property was picked up**

Run: `./mvnw -pl herald-bot spring-boot:run -q` in the background, or run:
```bash
./mvnw -pl herald-bot -q exec:java -Dexec.mainClass=com.herald.HeraldBotApplication 2>&1 | head -60
```
Expected: application starts without a yaml-parse error. Stop it with Ctrl-C (or skip this step if manual startup is not desired — the config key is validated by Spring AI lazily and Task 2's test run already confirms no YAML parse error).

If manual startup is undesirable, skip this step — the compile check in Task 1 and the bot module test run in Task 2.2.3 are sufficient.

---

## Self-review notes

- **Spec coverage:**
  - Spec Change 1 (YAML) → Task 1 ✓
  - Spec Change 2 (remove internal `Question`, refactor production code) → Task 2.2 ✓
  - Spec Change 3 (update tests) → Task 2.1 ✓
- **No placeholders:** all code shown in full, all commands concrete.
- **Type consistency:** the test helpers (`freeText`, `singleSelect`, `multiSelect`) all return `AskUserQuestionTool.Question`. Production `handleInternal` / `formatQuestions` signatures both take `List<Question>` where `Question` is the aliased `AskUserQuestionTool.Question` import. Keys used in assertions (`"Which calendar?"`, `"What time?"`, etc.) all match the question text passed to constructors.
- **Frequent commits:** two commits (one per task) — appropriate granularity for a tight refactor where the test and production changes must land together.
