package com.herald.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemindersAvailabilityCheckerTest {

    @Test
    void unavailableOnNonMacOs() {
        var checker = new RemindersAvailabilityChecker(
                cmd -> new RemindersAvailabilityChecker.CommandResult(0, "reminders 0.3.0"),
                "Linux");
        checker.checkAvailability();

        assertThat(checker.isAvailable()).isFalse();
        assertThat(checker.isMac()).isFalse();
    }

    @Test
    void availableOnMacWhenCliReturnsVersion() {
        var checker = new RemindersAvailabilityChecker(
                cmd -> new RemindersAvailabilityChecker.CommandResult(0, "reminders 0.3.0"),
                "Mac OS X");
        checker.checkAvailability();

        assertThat(checker.isMac()).isTrue();
        assertThat(checker.isAvailable()).isTrue();
        assertThat(checker.getVersion()).isEqualTo("reminders 0.3.0");
    }

    @Test
    void unavailableOnMacWhenCliMissing() {
        var checker = new RemindersAvailabilityChecker(
                cmd -> { throw new RuntimeException("command not found"); },
                "Mac OS X");
        checker.checkAvailability();

        assertThat(checker.isMac()).isTrue();
        assertThat(checker.isAvailable()).isFalse();
    }

    @Test
    void unavailableOnMacWhenCliExitsNonZero() {
        var checker = new RemindersAvailabilityChecker(
                cmd -> new RemindersAvailabilityChecker.CommandResult(1, ""),
                "Mac OS X");
        checker.checkAvailability();

        assertThat(checker.isAvailable()).isFalse();
    }
}
