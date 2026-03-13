# Adopt Upstream AskUserQuestionTool Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Herald's custom `AskUserQuestionTool` with the upstream version from `spring-ai-agent-utils`, implementing the `QuestionHandler` interface backed by Telegram with inline keyboard buttons for structured options.

**Architecture:** The upstream `AskUserQuestionTool` uses a builder pattern with a pluggable `QuestionHandler` interface (`Map<String,String> handle(List<Question>)`). Herald's `TelegramQuestionHandler` will implement this interface, converting upstream `Question` objects (with `question`, `header`, `options[label,description]`, `multiSelect`) to Telegram messages. When options are present, inline keyboard buttons are sent; otherwise plain free-text input is used. A `@Bean` factory method builds the upstream tool, wiring in the handler (or a fallback when Telegram is not configured).

**Note on upstream dual-path design:** The upstream `askUserQuestion(List<Question>, Map<String,String> answers)` method accepts an optional pre-collected `answers` map. When `answers` is null/empty, the `QuestionHandler.handle()` is invoked. For the Telegram use case, answers are never pre-collected — the handler is always called, which blocks until the user replies via Telegram.

**Tech Stack:** Java 21, Spring Boot 4.0, Spring AI 2.0, spring-ai-agent-utils 0.5.0, pengrad java-telegram-bot-api 7.7.0

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `herald-bot/src/main/java/com/herald/telegram/TelegramSender.java` | Add `sendMessageWithKeyboard()` for inline keyboard buttons |
| Modify | `herald-bot/src/main/java/com/herald/telegram/TelegramQuestionHandler.java` | Implement upstream `QuestionHandler` interface; convert upstream Questions; send keyboard for options |
| Modify | `herald-bot/src/main/java/com/herald/telegram/TelegramPoller.java` | Handle `CallbackQuery` from inline keyboard button presses |
| Delete | `herald-bot/src/main/java/com/herald/tools/AskUserQuestionTool.java` | Remove Herald's custom tool (replaced by upstream) |
| Modify | `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` | Build upstream tool via builder; remove old `AskUserQuestionTool` param |
| Delete | `herald-bot/src/test/java/com/herald/tools/AskUserQuestionToolTest.java` | Remove tests for deleted custom tool |
| Modify | `herald-bot/src/test/java/com/herald/telegram/TelegramQuestionHandlerTest.java` | Update for renamed internal method + new upstream interface tests |
| Modify | `herald-bot/src/test/java/com/herald/telegram/TelegramSenderTest.java` | Add tests for keyboard sending |
| Modify | `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java` | Update wiring to use upstream tool |

---

## Chunk 1: TelegramSender Inline Keyboard Support

### Task 1: Add inline keyboard sending to TelegramSender

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/telegram/TelegramSender.java`
- Test: `herald-bot/src/test/java/com/herald/telegram/TelegramSenderTest.java`

- [ ] **Step 1: Write the failing test for sendMessageWithKeyboard**

In `TelegramSenderTest.java`, add a test that verifies `sendMessageWithKeyboard` sends a message with an `InlineKeyboardMarkup` reply markup:

```java
@Test
void sendMessageWithKeyboardSendsInlineButtons() {
    when(bot.execute(any(SendMessage.class)))
            .thenReturn(successResponse());

    List<String> options = List.of("Work", "Personal", "Family");
    sender.sendMessageWithKeyboard("Which calendar?", options);

    ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(bot).execute(captor.capture());

    SendMessage sent = captor.getValue();
    // Verify the message has a reply markup (inline keyboard)
    assertThat(sent.getParameters().get("reply_markup")).isNotNull();
    assertThat(sent.getParameters().get("text")).isEqualTo("Which calendar?");
}
```

Note: Check how `TelegramSenderTest` currently creates mock responses (`successResponse()` helper). Adapt the test to match the existing test style — use whatever mock setup pattern is already there.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw -pl herald-bot test -Dtest=TelegramSenderTest#sendMessageWithKeyboardSendsInlineButtons -Dsurefire.failIfNoTests=false`
Expected: FAIL — `sendMessageWithKeyboard` method does not exist

- [ ] **Step 3: Implement sendMessageWithKeyboard**

Add to `TelegramSender.java`:

```java
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;

/**
 * Send a message with inline keyboard buttons. Each option becomes a button row.
 * The button's callback data is the option label text.
 */
public void sendMessageWithKeyboard(String text, List<String> options) {
    InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
    for (String option : options) {
        keyboard.addRow(new InlineKeyboardButton(option).callbackData(option));
    }
    SendMessage request = new SendMessage(chatId, text).replyMarkup(keyboard);
    try {
        SendResponse response = bot.execute(request);
        if (!response.isOk()) {
            log.error("Failed to send keyboard message: {}", response.description());
        }
    } catch (Exception e) {
        log.error("Error sending keyboard message: {}", e.getMessage());
    }
}
```

`InlineKeyboardButton(String text)` is a single-arg constructor. The fluent `.callbackData(String)` setter returns `this`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw -pl herald-bot test -Dtest=TelegramSenderTest -Dsurefire.failIfNoTests=false`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add herald-bot/src/main/java/com/herald/telegram/TelegramSender.java herald-bot/src/test/java/com/herald/telegram/TelegramSenderTest.java
git commit -m "feat: add inline keyboard button support to TelegramSender"
```

---

## Chunk 2: TelegramPoller Callback Query Handling

### Task 2: Handle callback queries from inline keyboard button presses

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/telegram/TelegramPoller.java`
- Test: `herald-bot/src/test/java/com/herald/telegram/TelegramPollerTest.java` (check if exists, create if not)

- [ ] **Step 1: Write the failing test**

Add a test that verifies when a `CallbackQuery` arrives with data, TelegramPoller calls `questionHandler.resolveAnswer()` with that data and answers the callback:

```java
@Test
void callbackQueryResolvesAnswerAndAcknowledges() {
    CallbackQuery callbackQuery = mock(CallbackQuery.class);
    when(callbackQuery.id()).thenReturn("cb-123");
    when(callbackQuery.data()).thenReturn("Work");

    // callback query from allowed chat
    Message message = mock(Message.class);
    Chat chat = mock(Chat.class);
    when(chat.id()).thenReturn(Long.parseLong(ALLOWED_CHAT_ID));
    when(message.chat()).thenReturn(chat);
    when(callbackQuery.message()).thenReturn(message);

    Update update = mock(Update.class);
    when(update.updateId()).thenReturn(1);
    when(update.callbackQuery()).thenReturn(callbackQuery);
    when(update.message()).thenReturn(null);

    when(questionHandler.hasPendingQuestion()).thenReturn(true);
    when(questionHandler.resolveAnswer("Work")).thenReturn(true);

    // Simulate poll returning this update
    GetUpdatesResponse response = mock(GetUpdatesResponse.class);
    when(response.isOk()).thenReturn(true);
    when(response.updates()).thenReturn(List.of(update));
    when(bot.execute(any(GetUpdates.class))).thenReturn(response);
    when(bot.execute(any(AnswerCallbackQuery.class))).thenReturn(mock(BaseResponse.class));

    poller.poll();

    verify(questionHandler).resolveAnswer("Work");
    verify(bot).execute(any(AnswerCallbackQuery.class));
}
```

Also add a test for callback queries when no question is pending:

```java
@Test
void callbackQueryWithNoPendingQuestionIsAcknowledgedButIgnored() {
    CallbackQuery callbackQuery = mock(CallbackQuery.class);
    when(callbackQuery.id()).thenReturn("cb-456");
    when(callbackQuery.data()).thenReturn("Personal");

    Message message = mock(Message.class);
    Chat chat = mock(Chat.class);
    when(chat.id()).thenReturn(Long.parseLong(ALLOWED_CHAT_ID));
    when(message.chat()).thenReturn(chat);
    when(callbackQuery.message()).thenReturn(message);

    Update update = mock(Update.class);
    when(update.updateId()).thenReturn(1);
    when(update.callbackQuery()).thenReturn(callbackQuery);
    when(update.message()).thenReturn(null);

    when(questionHandler.hasPendingQuestion()).thenReturn(false);

    GetUpdatesResponse response = mock(GetUpdatesResponse.class);
    when(response.isOk()).thenReturn(true);
    when(response.updates()).thenReturn(List.of(update));
    when(bot.execute(any(GetUpdates.class))).thenReturn(response);
    when(bot.execute(any(AnswerCallbackQuery.class))).thenReturn(mock(BaseResponse.class));

    poller.poll();

    verify(bot).execute(any(AnswerCallbackQuery.class));
    verify(questionHandler, never()).resolveAnswer(anyString());
}
```

Adapt to match the existing test style in `TelegramPollerTest.java`. If no test file exists, create one with proper mock setup matching the constructor params.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw -pl herald-bot test -Dtest=TelegramPollerTest#callbackQueryResolvesAnswerAndAcknowledges -Dsurefire.failIfNoTests=false`
Expected: FAIL — callback query not handled

- [ ] **Step 3: Implement callback query handling**

In `TelegramPoller.java`, add imports and modify `processUpdate`:

```java
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
```

Add to `processUpdate(Update update)` **before** the existing message handling:

```java
// Handle inline keyboard button presses
CallbackQuery callbackQuery = update.callbackQuery();
if (callbackQuery != null) {
    handleCallbackQuery(callbackQuery);
    return;
}
```

Add the handler method:

```java
private void handleCallbackQuery(CallbackQuery callbackQuery) {
    // Verify the callback came from the allowed chat before acknowledging
    if (callbackQuery.message() != null) {
        String chatId = String.valueOf(callbackQuery.message().chat().id());
        if (!chatId.equals(allowedChatId)) {
            log.debug("Dropping callback from unauthorized chat: {}", chatId);
            return;
        }
    }

    // Acknowledge the callback to dismiss the loading spinner
    try {
        bot.execute(new AnswerCallbackQuery(callbackQuery.id()));
    } catch (Exception e) {
        log.warn("Failed to answer callback query: {}", e.getMessage());
    }

    String data = callbackQuery.data();
    if (data != null && questionHandler.hasPendingQuestion()) {
        if (questionHandler.resolveAnswer(data)) {
            log.info("Callback query resolved pending question with: {}", data);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw -pl herald-bot test -Dtest=TelegramPollerTest -Dsurefire.failIfNoTests=false`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add herald-bot/src/main/java/com/herald/telegram/TelegramPoller.java herald-bot/src/test/java/com/herald/telegram/TelegramPollerTest.java
git commit -m "feat: handle inline keyboard callback queries in TelegramPoller"
```

---

## Chunk 3: TelegramQuestionHandler Implements Upstream QuestionHandler

### Task 3: Implement the upstream QuestionHandler interface

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/telegram/TelegramQuestionHandler.java`
- Modify: `herald-bot/src/test/java/com/herald/telegram/TelegramQuestionHandlerTest.java`

**Key Design Decisions:**
- The upstream `QuestionHandler` interface: `Map<String, String> handle(List<AskUserQuestionTool.Question> questions)`
- Herald's internal `handle(List<Question>)` has the same erased signature — **must rename** the internal method to avoid erasure conflict
- Rename internal `handle(List<Question>)` → `handleInternal(List<Question>)`
- The upstream interface `handle()` converts upstream questions → internal questions, delegates to `handleInternal`, then remaps result keys from internal IDs to question text (upstream validates keys match `question.question()`)
- When upstream questions have options, use `TelegramSender.sendMessageWithKeyboard()` for single-select
- Multi-select and free-text fall back to text-based messaging (existing format)

**Upstream Question structure:**
```
record Question(String question, String header, List<Option> options, Boolean multiSelect)
record Option(String label, String description)
```

**Internal Question structure:**
```
record Question(String id, String text, List<String> options, SelectionType selectionType)
```

- [ ] **Step 3.1: Update existing tests for renamed handleInternal method**

In `TelegramQuestionHandlerTest.java`, rename all calls from `handler.handle(...)` to `handler.handleInternal(...)`. The following tests call `handle()` directly:
- `handleReturnsEmptyMapForNullQuestions` → `handleInternalReturnsEmptyMapForNullQuestions`
- `handleReturnsEmptyMapForEmptyQuestions` → `handleInternalReturnsEmptyMapForEmptyQuestions`
- `handleMultipleQuestionsFormatsCorrectly` — change `handler.handle(questions)` to `handler.handleInternal(questions)`
- `handleWithCustomQuestionIdsUsesThemAsKeys` — change `handler.handle(questions)` to `handler.handleInternal(questions)`
- `handleRejectsSecondConcurrentQuestion` — change both calls
- `handleReturnsEmptyMapOnTimeout` — change the call

Also update `askQuestion`-based tests that indirectly call `handle` (these don't need renaming since `askQuestion` calls `handleInternal` internally).

- [ ] **Step 3.2: Run tests to verify they fail (method doesn't exist yet)**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw -pl herald-bot test -Dtest=TelegramQuestionHandlerTest -Dsurefire.failIfNoTests=false`
Expected: FAIL — `handleInternal` not found

- [ ] **Step 3.3: Rename internal handle → handleInternal**

In `TelegramQuestionHandler.java`:
1. Rename `public Map<String, String> handle(List<Question> questions)` → `public Map<String, String> handleInternal(List<Question> questions)`
2. Update `askQuestion(Question question)` to call `handleInternal` instead of `handle`

- [ ] **Step 3.4: Run tests to verify rename passes**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw -pl herald-bot test -Dtest=TelegramQuestionHandlerTest -Dsurefire.failIfNoTests=false`
Expected: ALL PASS

- [ ] **Step 3.5: Write failing test for upstream QuestionHandler.handle()**

Add to `TelegramQuestionHandlerTest.java`:

```java
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question.Option;

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
    // Multi-select uses text-based format, not keyboard
    verify(sender).sendMessage(anyString());
    verify(sender, never()).sendMessageWithKeyboard(anyString(), anyList());
}
```

Also add a test for multiple upstream questions (falls back to text, not keyboard):

```java
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
    // Multiple questions use text-based format, not keyboard
    verify(sender).sendMessage(anyString());
    verify(sender, never()).sendMessageWithKeyboard(anyString(), anyList());
}
```

- [ ] **Step 3.6: Run tests to verify they fail**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw -pl herald-bot test -Dtest=TelegramQuestionHandlerTest -Dsurefire.failIfNoTests=false`
Expected: FAIL — upstream `handle` method not implemented

- [ ] **Step 3.7: Implement upstream QuestionHandler interface**

In `TelegramQuestionHandler.java`:

1. Add interface implementation:
```java
import org.springaicommunity.agent.tools.AskUserQuestionTool;

public class TelegramQuestionHandler implements AskUserQuestionTool.QuestionHandler {
```

2. Add the `handle(List<AskUserQuestionTool.Question>)` method:

```java
/**
 * Implements the upstream QuestionHandler interface.
 * Converts upstream Question objects to internal format, sends via Telegram
 * (using inline keyboard for single-select with options, text for everything else),
 * and returns answers keyed by question text.
 */
@Override
public Map<String, String> handle(List<AskUserQuestionTool.Question> questions) {
    if (questions == null || questions.isEmpty()) {
        return Map.of();
    }

    // Convert upstream questions to internal format
    List<Question> internal = new ArrayList<>();
    for (var q : questions) {
        List<String> optionLabels = q.options() != null
                ? q.options().stream().map(AskUserQuestionTool.Question.Option::label).toList()
                : List.of();

        Question.SelectionType selType;
        if (optionLabels.isEmpty()) {
            selType = Question.SelectionType.FREE_TEXT;
        } else if (Boolean.TRUE.equals(q.multiSelect())) {
            selType = Question.SelectionType.MULTI_SELECT;
        } else {
            selType = Question.SelectionType.SINGLE_SELECT;
        }

        internal.add(new Question(q.question(), q.question(), optionLabels, selType));
    }

    // Determine if we should use inline keyboard (single question, single-select, has options)
    boolean useKeyboard = internal.size() == 1
            && internal.getFirst().selectionType() == Question.SelectionType.SINGLE_SELECT
            && !internal.getFirst().options().isEmpty();

    Map<String, String> internalResult;
    if (useKeyboard) {
        internalResult = handleWithKeyboard(internal.getFirst());
    } else {
        internalResult = handleInternal(internal);
    }

    // Remap keys: internal uses question ID (which we set to question text) → question text
    // Since we set id = question text above, keys already match what upstream expects
    return internalResult;
}
```

3. Refactor to extract shared blocking logic. The existing `handleInternal` and the new keyboard path share the same CAS/pending/timeout pattern. Extract the common logic into a private `sendAndAwaitReply` method, then have both paths use it:

```java
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
```

Then refactor `handleInternal` to use it (replace the CAS/future/try-catch block):

```java
public Map<String, String> handleInternal(List<Question> questions) {
    if (questions == null || questions.isEmpty()) {
        return Map.of();
    }

    String batchId = UUID.randomUUID().toString().substring(0, 8);
    String formatted = formatQuestions(batchId, questions);
    Optional<String> reply = sendAndAwaitReply(() -> sender.sendMessage(formatted));

    return reply.map(r -> buildAnswerMap(questions, r)).orElse(Map.of());
}
```

Note: The `batchId` is now generated inside `sendAndAwaitReply`, so `formatQuestions` no longer receives it from outside. Adjust `handleInternal` to pass the formatting as a closure that captures the batchId, or generate the batchId before calling `sendAndAwaitReply` and pass it in. The simplest approach: keep batchId generation in `handleInternal`, format the message, then pass `() -> sender.sendMessage(formatted)` to `sendAndAwaitReply`. Remove batchId generation from `sendAndAwaitReply` — just keep the CAS/future/timeout logic there.

And add the keyboard-sending path:

```java
private Map<String, String> handleWithKeyboard(Question question) {
    String batchId = UUID.randomUUID().toString().substring(0, 8);
    String messageText = "Question from Herald [" + batchId + "]:\n\n" + question.text();
    Optional<String> reply = sendAndAwaitReply(
            () -> sender.sendMessageWithKeyboard(messageText, question.options()));

    String key = question.id() != null ? question.id() : "q1";
    return reply.map(r -> Map.of(key, r)).orElse(Map.of());
}
```

Add import:
```java
import java.util.Optional;
```

4. Add missing import:
```java
import java.util.ArrayList;
```

- [ ] **Step 3.8: Run tests to verify they pass**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw -pl herald-bot test -Dtest=TelegramQuestionHandlerTest -Dsurefire.failIfNoTests=false`
Expected: ALL PASS

- [ ] **Step 3.9: Commit**

```bash
git add herald-bot/src/main/java/com/herald/telegram/TelegramQuestionHandler.java herald-bot/src/test/java/com/herald/telegram/TelegramQuestionHandlerTest.java
git commit -m "feat: implement upstream QuestionHandler interface in TelegramQuestionHandler

Maps upstream structured questions to Telegram messages with inline
keyboard buttons for single-select options. Multi-select and free-text
questions fall back to text-based messaging."
```

---

## Chunk 4: Rewire Config, Delete Custom Tool, Update Integration Tests

### Task 4: Delete Herald's custom AskUserQuestionTool

**Files:**
- Delete: `herald-bot/src/main/java/com/herald/tools/AskUserQuestionTool.java`
- Delete: `herald-bot/src/test/java/com/herald/tools/AskUserQuestionToolTest.java`

- [ ] **Step 4.1: Delete the custom tool and its tests**

```bash
rm herald-bot/src/main/java/com/herald/tools/AskUserQuestionTool.java
rm herald-bot/src/test/java/com/herald/tools/AskUserQuestionToolTest.java
```

- [ ] **Step 4.2: Verify compilation fails (expected — config still references it)**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw -pl herald-bot compile 2>&1 | tail -20`
Expected: FAIL — `HeraldAgentConfig` references deleted class

### Task 5: Rewire HeraldAgentConfig to use upstream AskUserQuestionTool

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`

- [ ] **Step 5.1: Update HeraldAgentConfig**

Changes to make:

1. Replace import:
```java
// REMOVE:
import com.herald.tools.AskUserQuestionTool;
// ADD:
import com.herald.telegram.TelegramQuestionHandler;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.beans.factory.ObjectProvider;
```

2. Update `activeToolNames` — change `"ask"` to `"askUserQuestion"`:
```java
return List.of("memory", "shell", "filesystem", "todo", "askUserQuestion", "task", "taskOutput", "skills", "gws", "web", "telegram_send", "cron");
```

3. Replace `AskUserQuestionTool askTool` parameter in `modelSwitcher()` with `ObjectProvider<TelegramQuestionHandler> questionHandlerProvider`:
```java
public ModelSwitcher modelSwitcher(
        @Qualifier("anthropicChatModel") ChatModel chatModel,
        HeraldConfig config,
        ChatMemory chatMemory,
        MemoryTools memoryTools,
        HeraldShellDecorator shellDecorator,
        FileSystemTools fsTools,
        TodoWriteTool todoTool,
        ObjectProvider<TelegramQuestionHandler> questionHandlerProvider,  // CHANGED
        TelegramSendTool telegramSendTool,
        ...
```

4. Build the upstream tool inside `modelSwitcher()`, before the client builder factory:
```java
// Build upstream AskUserQuestionTool with Telegram-backed handler (or no-op fallback)
TelegramQuestionHandler telegramHandler = questionHandlerProvider.getIfAvailable();
AskUserQuestionTool.QuestionHandler questionHandler = telegramHandler != null
        ? telegramHandler
        : questions -> {
            log.warn("AskUserQuestion called but Telegram is not configured — returning empty");
            return Map.of();
        };
AskUserQuestionTool askTool = AskUserQuestionTool.builder()
        .questionHandler(questionHandler)
        .answersValidation(telegramHandler != null)
        .build();
```

Add a logger field at class level:
```java
private static final Logger log = LoggerFactory.getLogger(HeraldAgentConfig.class);
```

5. Keep `askTool` in `defaultTools()` (the upstream `AskUserQuestionTool` is a POJO with `@Tool`-annotated methods, not a `ToolCallback`):
```java
Function<ChatModel, ChatClient.Builder> clientBuilderFactory = cm ->
        ChatClient.builder(cm)
                .defaultSystem(systemPrompt)
                .defaultTools(memoryTools, shellDecorator, fsTools, todoTool, askTool, telegramSendTool, gwsTools, webTools, cronTools)  // askTool stays here
                .defaultToolCallbacks(taskTool, taskOutputTool, reloadableSkillsTool)  // unchanged
                ...
```

- [ ] **Step 5.2: Verify compilation passes**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw -pl herald-bot compile`
Expected: BUILD SUCCESS

- [ ] **Step 5.3: Commit**

```bash
git add -A
git commit -m "feat: wire upstream AskUserQuestionTool, remove custom implementation

Builds the upstream tool via builder pattern with TelegramQuestionHandler
as the QuestionHandler. Falls back to a no-op handler when Telegram
is not configured."
```

### Task 6: Update integration tests

**Files:**
- Modify: `herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java`

- [ ] **Step 6.1: Update HeraldAgentConfigIntegrationTest**

The test currently passes `mock(AskUserQuestionTool.class)` as the `askTool` parameter. Change all three test methods to pass `mock(ObjectProvider.class)` instead:

1. Replace import:
```java
// REMOVE:
import com.herald.tools.AskUserQuestionTool;
// ADD:
import org.springframework.beans.factory.ObjectProvider;
```

2. In all three `modelSwitcher(...)` calls, replace `mock(AskUserQuestionTool.class)` with `mock(ObjectProvider.class)`:

```java
// In each of the 3 tests, change:
//   mock(AskUserQuestionTool.class)
// to:
//   mock(ObjectProvider.class)
```

The `ObjectProvider.getIfAvailable()` returns null by default from a mock, which triggers the no-op fallback handler — exactly what we want for tests.

- [ ] **Step 6.2: Run all tests**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw -pl herald-bot test`
Expected: ALL PASS

- [ ] **Step 6.3: Commit**

```bash
git add herald-bot/src/test/java/com/herald/agent/HeraldAgentConfigIntegrationTest.java
git commit -m "test: update integration tests for upstream AskUserQuestionTool wiring"
```

---

## Chunk 5: Remove HeraldShellDecorator TODO comment

### Task 7: Clean up stale TODO reference

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/tools/HeraldShellDecorator.java:111-112`

- [ ] **Step 7.1: Update the stale TODO**

The TODO at line ~112 says "AskUserQuestionTool integration is a follow-up". The AskUserQuestionTool part is now done, but the underlying TelegramPoller wiring for `confirmCommand` is still a separate TODO. Update the comment to remove the stale AskUserQuestionTool reference while preserving the TelegramPoller wiring TODO.

- [ ] **Step 7.2: Full test suite**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw test`
Expected: ALL PASS

- [ ] **Step 7.3: Final commit**

```bash
git add herald-bot/src/main/java/com/herald/tools/HeraldShellDecorator.java
git commit -m "chore: remove stale AskUserQuestionTool TODO from HeraldShellDecorator"
```
