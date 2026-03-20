package com.herald.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GwsAvailabilityCheckerTest {

    @Test
    void marksAvailableWhenGwsReturnsVersion() {
        GwsAvailabilityChecker checker = new GwsAvailabilityChecker(
                command -> new GwsAvailabilityChecker.CommandResult(0, "gws version 1.2.3\n"));

        checker.checkGwsAvailability();

        assertThat(checker.isAvailable()).isTrue();
        assertThat(checker.getVersion()).isEqualTo("gws version 1.2.3");
    }

    @Test
    void marksUnavailableWhenGwsNotFound() {
        GwsAvailabilityChecker checker = new GwsAvailabilityChecker(
                command -> new GwsAvailabilityChecker.CommandResult(127, ""));

        checker.checkGwsAvailability();

        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.getVersion()).isNull();
    }

    @Test
    void marksUnavailableWhenCommandThrows() {
        GwsAvailabilityChecker checker = new GwsAvailabilityChecker(
                command -> { throw new RuntimeException("not found"); });

        checker.checkGwsAvailability();

        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.getVersion()).isNull();
    }

    @Test
    void marksUnavailableWhenOutputIsBlank() {
        GwsAvailabilityChecker checker = new GwsAvailabilityChecker(
                command -> new GwsAvailabilityChecker.CommandResult(0, "   "));

        checker.checkGwsAvailability();

        assertThat(checker.isAvailable()).isFalse();
    }
}
