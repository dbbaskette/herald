package com.herald.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GwsToolsTest {

    @Test
    void returnsUnavailableErrorWhenGwsNotConfigured() {
        GwsAvailabilityChecker checker = new GwsAvailabilityChecker(
                command -> new GwsAvailabilityChecker.CommandResult(127, ""));
        checker.checkGwsAvailability();

        GwsTools tools = new GwsTools(checker);

        String result = tools.gmail_threads_list();
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("gws auth login");
    }

    @Test
    void calendarReturnsUnavailableErrorWhenGwsNotConfigured() {
        GwsAvailabilityChecker checker = new GwsAvailabilityChecker(
                command -> new GwsAvailabilityChecker.CommandResult(127, ""));
        checker.checkGwsAvailability();

        GwsTools tools = new GwsTools(checker);

        String result = tools.calendar_events_list();
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("gws auth login");
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
