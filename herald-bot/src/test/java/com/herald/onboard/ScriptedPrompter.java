package com.herald.onboard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Test-only {@link Prompter} that returns scripted answers in order. Captures
 * the printed output so tests can assert on the wizard's user-facing flow
 * without touching real stdin/stdout.
 */
class ScriptedPrompter implements Prompter {

    private final Deque<String> answers;
    private final List<String> output = new ArrayList<>();

    ScriptedPrompter(String... scriptedAnswers) {
        this.answers = new ArrayDeque<>(List.of(scriptedAnswers));
    }

    @Override
    public String prompt(String message) {
        output.add(message);
        return answers.isEmpty() ? "" : answers.poll();
    }

    @Override
    public String promptSecret(String message) {
        return prompt(message);
    }

    @Override
    public boolean confirm(String message, boolean defaultYes) {
        String answer = prompt(message);
        if (answer.isBlank()) return defaultYes;
        return answer.toLowerCase().startsWith("y");
    }

    @Override
    public void waitForEnter(String message) {
        // No-op in tests.
        output.add(message);
    }

    @Override
    public void println(String line) {
        output.add(line);
    }

    String fullOutput() {
        return String.join("\n", output);
    }

    int answersRemaining() {
        return answers.size();
    }
}
