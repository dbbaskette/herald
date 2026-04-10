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
