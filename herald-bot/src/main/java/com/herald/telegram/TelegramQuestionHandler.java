package com.herald.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridges agent questions with Telegram messaging.
 * Formats questions as numbered Telegram messages, sends them via TelegramSender,
 * and blocks until the user replies or a timeout expires.
 */
@Component
@ConditionalOnProperty("herald.telegram.bot-token")
public class TelegramQuestionHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramQuestionHandler.class);
    public static final long TIMEOUT_MINUTES = 5;

    private final TelegramSender sender;
    private final ConcurrentHashMap<String, PendingQuestion> pendingQuestions = new ConcurrentHashMap<>();

    TelegramQuestionHandler(TelegramSender sender) {
        this.sender = sender;
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
     * Send multiple questions to the user via Telegram and block until they reply.
     * Returns a map of question ID to answer. On timeout, returns an empty map.
     */
    public Map<String, String> handle(List<Question> questions) {
        if (questions == null || questions.isEmpty()) {
            return Map.of();
        }

        String batchId = UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<String> future = new CompletableFuture<>();
        PendingQuestion pending = new PendingQuestion(batchId, future);
        pendingQuestions.put(batchId, pending);

        String formatted = formatQuestions(batchId, questions);
        sender.sendMessage(formatted);

        try {
            String reply = future.get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            log.info("Received answer for question batch {}", batchId);
            return buildAnswerMap(questions, reply);
        } catch (TimeoutException e) {
            log.warn("Question batch {} timed out after {} minutes", batchId, TIMEOUT_MINUTES);
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Question batch {} was interrupted", batchId);
            return Map.of();
        } catch (Exception e) {
            log.error("Error waiting for answer to question batch {}: {}", batchId, e.getMessage());
            return Map.of();
        } finally {
            pendingQuestions.remove(batchId);
        }
    }

    /**
     * Called by TelegramPoller when the user replies to a pending question.
     * Returns true if the reply was matched to a pending question.
     */
    public boolean resolveAnswer(String answer) {
        // Only one question batch can be pending at a time; resolve the most recent one
        PendingQuestion pending = pendingQuestions.values().stream().findFirst().orElse(null);
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
        return !pendingQuestions.isEmpty();
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
