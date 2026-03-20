package com.herald.telegram;

import com.herald.telegram.TelegramQuestionHandler.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool;

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
                new Question("Which calendar?", List.of("Work", "Personal", "Family")),
                new Question("What time?")
        );

        Map<String, String> result = handler.handleInternal(questions);

        assertThat(result).hasSize(2);
        assertThat(result).containsKeys("q1", "q2");
        assertThat(result.get("q1")).isEqualTo("A");
    }

    @Test
    void formatQuestionsIncludesOptionsAndSelectionHint() {
        List<Question> questions = List.of(
                new Question("Which calendar?", List.of("Work", "Personal", "Family"))
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
                new Question("Select tags:", List.of("urgent", "work", "home"),
                        Question.SelectionType.MULTI_SELECT)
        );

        String formatted = handler.formatQuestions("abc123", questions);

        assertThat(formatted).contains("Select multiple");
    }

    @Test
    void formatQuestionsNumbersMultipleQuestions() {
        List<Question> questions = List.of(
                new Question("First question?"),
                new Question("Second question?")
        );

        String formatted = handler.formatQuestions("abc123", questions);

        assertThat(formatted).contains("1. First question?");
        assertThat(formatted).contains("2. Second question?");
    }

    @Test
    void formatQuestionsSingleQuestionNotNumbered() {
        List<Question> questions = List.of(
                new Question("Only question?")
        );

        String formatted = handler.formatQuestions("abc123", questions);

        assertThat(formatted).doesNotContain("1.");
        assertThat(formatted).contains("Only question?");
    }

    @Test
    void handleWithCustomQuestionIdsUsesThemAsKeys() {
        CountDownLatch messageSent = new CountDownLatch(1);
        doAnswer(invocation -> {
            messageSent.countDown();
            return null;
        }).when(sender).sendMessage(anyString());

        CompletableFuture.runAsync(() -> {
            try {
                messageSent.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            handler.resolveAnswer("Work");
        });

        List<Question> questions = List.of(
                new Question("calendar-q", "Which calendar?", List.of("Work", "Personal"),
                        Question.SelectionType.SINGLE_SELECT)
        );

        Map<String, String> result = handler.handleInternal(questions);

        assertThat(result).containsKey("calendar-q");
        assertThat(result.get("calendar-q")).isEqualTo("Work");
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

            // Wait for the question to be sent (message sent = future is registered)
            assertThat(messageSent.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(handler.hasPendingQuestion()).isTrue();

            handler.resolveAnswer("yes");

            String result = askFuture.get(5, TimeUnit.SECONDS);
            assertThat(result).isEqualTo("yes");

            // After get() returns, the finally block has cleared the pending question
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
            // Start first question in background
            CompletableFuture<String> firstQuestion = CompletableFuture.supplyAsync(
                    () -> handler.askQuestion("first?"), executor);

            assertThat(messageSent.await(5, TimeUnit.SECONDS)).isTrue();

            // Try to send a second question while the first is pending
            assertThatThrownBy(() -> handler.handleInternal(List.of(new Question("second?"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already pending");

            // Clean up: resolve the first question
            handler.resolveAnswer("done");
            firstQuestion.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void handleReturnsEmptyMapOnTimeout() {
        // Use a very short timeout to test the timeout path
        TelegramQuestionHandler shortTimeoutHandler = new TelegramQuestionHandler(sender, 0);

        Map<String, String> result = shortTimeoutHandler.handleInternal(
                List.of(new Question("Will this timeout?")));

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
