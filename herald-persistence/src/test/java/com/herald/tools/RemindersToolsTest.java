package com.herald.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemindersToolsTest {

    private RemindersAvailabilityChecker checker;
    private RecordingRunner runner;

    @BeforeEach
    void setUp() {
        checker = mock(RemindersAvailabilityChecker.class);
        when(checker.isAvailable()).thenReturn(true);
        runner = new RecordingRunner();
    }

    @Test
    void listNamesReturnsJsonArrayFromCliOutput() {
        runner.queueResult(0, "Reminders\nGroceries\nWork\n", false);

        String json = new RemindersTools(checker, runner).reminders_list_names();

        assertThat(runner.lastCommand()).containsExactly("reminders", "show-lists");
        assertThat(json).isEqualTo("[\"Reminders\",\"Groceries\",\"Work\"]");
    }

    @Test
    void listNamesReturnsUnavailableErrorWhenCliMissing() {
        when(checker.isAvailable()).thenReturn(false);

        String json = new RemindersTools(checker, runner).reminders_list_names();

        assertThat(json).contains("not available");
        assertThat(runner.invocations).isEmpty();
    }

    @Test
    void showPassesListNameAndJsonFlag() {
        runner.queueResult(0, "[]", false);

        new RemindersTools(checker, runner).reminders_show("Groceries");

        assertThat(runner.lastCommand()).containsExactly(
                "reminders", "show", "Groceries", "--format", "json");
    }

    @Test
    void showWithNullListNameUsesShowAll() {
        runner.queueResult(0, "[]", false);

        new RemindersTools(checker, runner).reminders_show(null);

        assertThat(runner.lastCommand()).containsExactly(
                "reminders", "show-all", "--format", "json");
    }

    @Test
    void showReturnsEmptyArrayOnBlankOutput() {
        runner.queueResult(0, "", false);

        String json = new RemindersTools(checker, runner).reminders_show("Groceries");

        assertThat(json).isEqualTo("[]");
    }

    @Test
    void createPassesTitleAndOptionalFlags() {
        runner.queueResult(0, "Added to Groceries", false);

        String json = new RemindersTools(checker, runner)
                .reminders_create("Groceries", "Milk", "tomorrow 3pm", "2% only");

        assertThat(runner.lastCommand()).containsExactly(
                "reminders", "add", "Groceries", "Milk",
                "--due-date", "tomorrow 3pm",
                "--notes", "2% only");
        assertThat(json).contains("\"ok\": true").contains("\"operation\": \"create\"");
    }

    @Test
    void createOmitsDueDateAndNotesWhenBlank() {
        runner.queueResult(0, "Added", false);

        new RemindersTools(checker, runner).reminders_create("Groceries", "Eggs", "", null);

        assertThat(runner.lastCommand()).containsExactly(
                "reminders", "add", "Groceries", "Eggs");
    }

    @Test
    void createValidatesInputs() {
        String json = new RemindersTools(checker, runner).reminders_create("", "t", null, null);
        assertThat(json).contains("required");

        json = new RemindersTools(checker, runner).reminders_create("list", "", null, null);
        assertThat(json).contains("required");
    }

    @Test
    void completeByIndex() {
        runner.queueResult(0, "Completed", false);

        String json = new RemindersTools(checker, runner).reminders_complete("Groceries", 0);

        assertThat(runner.lastCommand()).containsExactly(
                "reminders", "complete", "Groceries", "0");
        assertThat(json).contains("\"operation\": \"complete\"");
    }

    @Test
    void completeRejectsNegativeIndex() {
        String json = new RemindersTools(checker, runner).reminders_complete("Groceries", -1);
        assertThat(json).contains("required");
        assertThat(runner.invocations).isEmpty();
    }

    @Test
    void deleteByIndex() {
        runner.queueResult(0, "Deleted", false);

        String json = new RemindersTools(checker, runner).reminders_delete("Groceries", 2);

        assertThat(runner.lastCommand()).containsExactly(
                "reminders", "delete", "Groceries", "2");
        assertThat(json).contains("\"operation\": \"delete\"");
    }

    @Test
    void nonZeroExitSurfacesAsErrorJson() {
        runner.queueResult(1, "no such list", false);

        String json = new RemindersTools(checker, runner).reminders_show("Missing");

        assertThat(json).contains("\"error\"").contains("exit code 1");
    }

    @Test
    void timeoutSurfacesAsErrorJson() {
        runner.queueResult(-1, "", true);

        String json = new RemindersTools(checker, runner).reminders_list_names();

        assertThat(json).contains("timed out");
    }

    // --- helpers ---

    private static class RecordingRunner implements RemindersTools.ProcessRunner {
        final List<List<String>> invocations = new ArrayList<>();
        private final List<RemindersTools.ProcessResult> results = new ArrayList<>();

        void queueResult(int exit, String out, boolean timedOut) {
            results.add(new RemindersTools.ProcessResult(exit, out, timedOut));
        }

        @Override
        public RemindersTools.ProcessResult run(List<String> command) {
            invocations.add(List.copyOf(command));
            if (results.isEmpty()) {
                return new RemindersTools.ProcessResult(0, "", false);
            }
            return results.remove(0);
        }

        List<String> lastCommand() {
            return invocations.get(invocations.size() - 1);
        }
    }
}
