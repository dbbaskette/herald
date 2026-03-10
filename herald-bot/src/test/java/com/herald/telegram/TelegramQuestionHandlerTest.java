package com.herald.telegram;

import com.herald.telegram.TelegramQuestionHandler.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void handleReturnsEmptyMapForNullQuestions() {
        Map<String, String> result = handler.handle(null);
        assertThat(result).isEmpty();
    }

    @Test
    void handleReturnsEmptyMapForEmptyQuestions() {
        Map<String, String> result = handler.handle(List.of());
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

        Map<String, String> result = handler.handle(questions);

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

        Map<String, String> result = handler.handle(questions);

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
            assertThatThrownBy(() -> handler.handle(List.of(new Question("second?"))))
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

        Map<String, String> result = shortTimeoutHandler.handle(
                List.of(new Question("Will this timeout?")));

        assertThat(result).isEmpty();
        verify(sender).sendMessage(anyString());
    }
}
