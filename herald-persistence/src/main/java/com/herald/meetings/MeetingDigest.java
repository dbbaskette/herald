package com.herald.meetings;

import java.util.List;

/**
 * Normalized view of a completed MeetingNotes meeting, shared by both ingestion
 * paths: the real-time webhook (built from the posted payload) and the daily
 * date-query backstop (built by {@link MeetingNotesCatalog} from the app's
 * read-only SQLite catalog + on-disk {@code summary.md} / {@code action-items.json}).
 */
public record MeetingDigest(
        String id,
        String slug,
        String title,
        String startedAt,
        Integer durationS,
        String status,
        List<String> attendees,
        String summaryMarkdown,
        List<ActionItem> actionItems) {

    public record ActionItem(String text, String owner, String dueDate) {}
}
