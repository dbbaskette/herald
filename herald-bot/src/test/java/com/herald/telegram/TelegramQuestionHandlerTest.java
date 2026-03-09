package com.herald.telegram;

import com.herald.telegram.TelegramQuestionHandler.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
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
        // Resolve the answer from another thread shortly after asking
        doAnswer(invocation -> {
            CompletableFuture.runAsync(() -> {
                // Small delay to ensure the future is registered
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                handler.resolveAnswer("42");
            });
            return null;
        }).when(sender).sendMessage(anyString());

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
        doAnswer(invocation -> {
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                handler.resolveAnswer("A");
            });
            return null;
        }).when(sender).sendMessage(anyString());

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
        doAnswer(invocation -> {
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                handler.resolveAnswer("Work");
            });
            return null;
        }).when(sender).sendMessage(anyString());

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
        try {
            // Start asking in background
            CompletableFuture<String> askFuture = CompletableFuture.supplyAsync(
                    () -> handler.askQuestion("test?"), executor);

            // Wait for the question to be sent
            Thread.sleep(100);

            assertThat(handler.hasPendingQuestion()).isTrue();

            // Resolve it
            handler.resolveAnswer("yes");

            String result = askFuture.get();
            assertThat(result).isEqualTo("yes");

            // After resolution, pending should be cleared
            // Small delay for cleanup
            Thread.sleep(50);
            assertThat(handler.hasPendingQuestion()).isFalse();
        } finally {
            executor.shutdown();
        }
    }
}
