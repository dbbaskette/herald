package com.herald.memory;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import static org.assertj.core.api.Assertions.assertThat;

class ChatArchivalJobTest {

    @Test
    void countTriggerFiresAboveThreshold() {
        assertThat(ChatArchivalJob.shouldArchive(6, false, 5, 2)).isTrue();
    }

    @Test
    void countTriggerDoesNotFireAtOrBelowThreshold() {
        assertThat(ChatArchivalJob.shouldArchive(5, false, 5, 2)).isFalse();
        assertThat(ChatArchivalJob.shouldArchive(3, false, 5, 2)).isFalse();
    }

    @Test
    void idleTriggerFiresWithEnoughMessages() {
        assertThat(ChatArchivalJob.shouldArchive(2, true, 5, 2)).isTrue();
        assertThat(ChatArchivalJob.shouldArchive(3, true, 5, 2)).isTrue();
    }

    @Test
    void idleTriggerDoesNotFireWithOneMessage() {
        assertThat(ChatArchivalJob.shouldArchive(1, true, 5, 2)).isFalse();
    }

    @Test
    void noTriggerWhenNotIdleAndBelowThreshold() {
        assertThat(ChatArchivalJob.shouldArchive(3, false, 5, 2)).isFalse();
    }

    @Test
    void isIdleReturnsTrueForOldTimestamp() {
        String old = LocalDateTime.now().minusMinutes(45).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertThat(ChatArchivalJob.isTimestampIdle(old, 30)).isTrue();
    }

    @Test
    void isIdleReturnsFalseForRecentTimestamp() {
        String recent = LocalDateTime.now().minusMinutes(5).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertThat(ChatArchivalJob.isTimestampIdle(recent, 30)).isFalse();
    }

    @Test
    void isIdleReturnsFalseForNull() {
        assertThat(ChatArchivalJob.isTimestampIdle(null, 30)).isFalse();
    }
}
