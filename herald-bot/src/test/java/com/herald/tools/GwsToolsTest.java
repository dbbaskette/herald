package com.herald.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GwsToolsTest {

    // Null JdbcTemplate is safe — getSetting() catches exceptions gracefully,
    // and tests using the 3-arg constructor bypass DB-dependent code paths.
    private static final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = null;

    private GwsAvailabilityChecker unavailableChecker() {
        GwsAvailabilityChecker checker = new GwsAvailabilityChecker(
                command -> new GwsAvailabilityChecker.CommandResult(127, ""));
        checker.checkGwsAvailability();
        return checker;
    }

    private GwsAvailabilityChecker availableChecker() {
        GwsAvailabilityChecker checker = new GwsAvailabilityChecker(
                command -> new GwsAvailabilityChecker.CommandResult(0, "gws 1.0.0"));
        checker.checkGwsAvailability();
        return checker;
    }

    @Test
    void returnsUnavailableErrorWhenGwsNotConfigured() {
        GwsTools tools = new GwsTools(unavailableChecker(), jdbcTemplate);

        String result = tools.gmail_threads_list();
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("gws auth login");
    }

    @Test
    void calendarReturnsUnavailableErrorWhenGwsNotConfigured() {
        GwsTools tools = new GwsTools(unavailableChecker(), jdbcTemplate);

        String result = tools.calendar_events_list();
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("gws auth login");
    }

    @Test
    void returnsRawJsonOnSuccess() {
        String expectedJson = "[{\"id\":\"123\",\"subject\":\"Hello\"}]";
        GwsTools tools = new GwsTools(availableChecker(), jdbcTemplate,
                (command, env) -> new GwsTools.ProcessResult(0, expectedJson, false));

        String result = tools.gmail_threads_list();
        assertThat(result).isEqualTo(expectedJson);
    }

    @Test
    void returnsEmptyArrayWhenOutputIsEmpty() {
        GwsTools tools = new GwsTools(availableChecker(), jdbcTemplate,
                (command, env) -> new GwsTools.ProcessResult(0, "", false));

        String result = tools.gmail_threads_list();
        assertThat(result).isEqualTo("[]");
    }

    @Test
    void calendarReturnsRawJsonOnSuccess() {
        String expectedJson = "[{\"id\":\"evt1\",\"title\":\"Meeting\"}]";
        GwsTools tools = new GwsTools(availableChecker(), jdbcTemplate,
                (command, env) -> new GwsTools.ProcessResult(0, expectedJson, false));

        String result = tools.calendar_events_list();
        assertThat(result).isEqualTo(expectedJson);
    }

    @Test
    void returnsErrorJsonOnTimeout() {
        GwsTools tools = new GwsTools(availableChecker(), jdbcTemplate,
                (command, env) -> new GwsTools.ProcessResult(-1, "", true));

        String result = tools.gmail_threads_list();
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("timed out");
    }

    @Test
    void returnsErrorJsonOnNonZeroExitCode() {
        GwsTools tools = new GwsTools(availableChecker(), jdbcTemplate,
                (command, env) -> new GwsTools.ProcessResult(1, "auth required", false));

        String result = tools.gmail_threads_list();
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("exit code 1");
        assertThat(result).contains("auth required");
    }

    @Test
    void returnsErrorJsonWhenProcessRunnerThrows() {
        GwsTools tools = new GwsTools(availableChecker(), jdbcTemplate,
                (command, env) -> { throw new RuntimeException("No such file"); });

        String result = tools.gmail_threads_list();
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("No such file");
    }

    @Test
    void returnsErrorJsonOnIOException() {
        GwsTools tools = new GwsTools(availableChecker(), jdbcTemplate,
                (command, env) -> { throw new IOException("No such file or directory"); });

        String result = tools.gmail_threads_list();
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("Failed to execute gws command");
        assertThat(result).contains("No such file or directory");
    }

    @Test
    void passesCorrectCommandForGmail() {
        List<String>[] captured = new List[1];
        GwsTools tools = new GwsTools(availableChecker(), jdbcTemplate,
                (command, env) -> { captured[0] = command; return new GwsTools.ProcessResult(0, "[]", false); });

        tools.gmail_threads_list();
        assertThat(captured[0]).containsExactly("gws", "gmail", "users", "threads", "list",
                "--params", "{\"userId\": \"me\", \"maxResults\": 10}", "--format", "json");
    }

    @Test
    void passesCorrectCommandForCalendar() {
        List<String>[] captured = new List[1];
        GwsTools tools = new GwsTools(availableChecker(), jdbcTemplate,
                (command, env) -> { captured[0] = command; return new GwsTools.ProcessResult(0, "[]", false); });

        tools.calendar_events_list();
        assertThat(captured[0]).startsWith("gws", "calendar", "events", "list", "--params");
    }

    @Test
    void escapesSpecialCharactersInErrorOutput() {
        GwsTools tools = new GwsTools(availableChecker(), jdbcTemplate,
                (command, env) -> new GwsTools.ProcessResult(1, "line1\nline2\ttab\r\nquote\"end", false));

        String result = tools.calendar_events_list();
        assertThat(result).contains("\\n");
        assertThat(result).contains("\\t");
        assertThat(result).contains("\\r");
        assertThat(result).contains("\\\"");
    }

    @Test
    void gmailThreadsListHasToolAnnotation() throws NoSuchMethodException {
        Method method = GwsTools.class.getMethod("gmail_threads_list");
        Tool annotation = method.getAnnotation(Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.description()).containsIgnoringCase("gmail");
    }

    @Test
    void calendarEventsListHasToolAnnotation() throws NoSuchMethodException {
        Method method = GwsTools.class.getMethod("calendar_events_list");
        Tool annotation = method.getAnnotation(Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.description()).containsIgnoringCase("calendar");
    }
}
