package com.herald.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges agent questions with Telegram messaging.
 * Formats questions as numbered Telegram messages, sends them via TelegramSender,
 * and blocks until the user replies or a timeout expires.
 *
 * <p>Note: This is a standalone implementation. When spring-ai-agent-utils exports
 * a QuestionHandler interface, this class should be updated to implement it and
 * align with the library's Question type to avoid a breaking change.</p>
 */
@Component
@ConditionalOnProperty("herald.telegram.bot-token")
public class TelegramQuestionHandler {

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

    /**
     * A single question with optional options for the user to choose from.
     */
    public record Question(String id, String text, List<String> options, SelectionType selectionType) {

        public enum SelectionType {
            SINGLE_SELECT,
            MULTI_SELECT,
            FREE_TEXT
        }

        public Question(String text) {
            this(null, text, List.of(), SelectionType.FREE_TEXT);
        }

        public Question(String text, List<String> options) {
            this(null, text, options, SelectionType.SINGLE_SELECT);
        }

        public Question(String text, List<String> options, SelectionType selectionType) {
            this(null, text, options, selectionType);
        }
    }

    record PendingQuestion(String questionId, CompletableFuture<String> future) {
    }

    /**
     * Send a single question to the user via Telegram and block until they reply.
     * Returns the user's answer, or an empty string on timeout.
     */
    public String askQuestion(String question) {
        return askQuestion(new Question(question));
    }

    /**
     * Send a structured question to the user via Telegram and block until they reply.
     * Returns the user's answer, or an empty string on timeout.
     */
    public String askQuestion(Question question) {
        Map<String, String> results = handle(List.of(question));
        return results.values().stream().findFirst().orElse("");
    }

    /**
     * Send questions to the user via Telegram and block until they reply.
     * Returns a map of question ID to answer. On timeout, returns an empty map.
     *
     * <p>Only one question batch may be pending at a time. If a batch is already
     * pending, this method throws {@link IllegalStateException}.</p>
     *
     * <p><b>Limitation:</b> When multiple questions are sent in a single batch,
     * the user provides a single reply that is assigned to all question keys.
     * For independent answers per question, callers should send one question at a time.</p>
     */
    public Map<String, String> handle(List<Question> questions) {
        if (questions == null || questions.isEmpty()) {
            return Map.of();
        }

        String batchId = UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<String> future = new CompletableFuture<>();
        PendingQuestion pending = new PendingQuestion(batchId, future);

        if (!pendingQuestion.compareAndSet(null, pending)) {
            throw new IllegalStateException(
                    "A question is already pending. Only one question batch may be active at a time.");
        }

        String formatted = formatQuestions(batchId, questions);
        sender.sendMessage(formatted);

        try {
            String reply = future.get(timeoutMinutes, TimeUnit.MINUTES);
            log.info("Received answer for question batch {}", batchId);
            return buildAnswerMap(questions, reply);
        } catch (TimeoutException e) {
            log.warn("Question batch {} timed out after {} minutes", batchId, timeoutMinutes);
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Question batch {} was interrupted", batchId);
            return Map.of();
        } catch (Exception e) {
            log.error("Error waiting for answer to question batch {}: {}", batchId, e.getMessage());
            return Map.of();
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
            sb.append(q.text()).append("\n");

            if (!q.options().isEmpty()) {
                for (int j = 0; j < q.options().size(); j++) {
                    sb.append("  ").append((char) ('A' + j)).append(") ").append(q.options().get(j)).append("\n");
                }
                if (q.selectionType() == Question.SelectionType.MULTI_SELECT) {
                    sb.append("(Select multiple: e.g. A, C)\n");
                } else if (q.selectionType() == Question.SelectionType.SINGLE_SELECT) {
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
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            String key = q.id() != null ? q.id() : "q" + (i + 1);
            answers.put(key, reply);
        }
        return answers;
    }
}
