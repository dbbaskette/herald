package com.herald.meetings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Dedup ledger for ingested MeetingNotes meetings. A meeting can reach Herald
 * twice — once via the real-time webhook and again via the daily date-query
 * backstop — so this records which meeting ids have already been enriched and
 * lets callers claim a meeting atomically before doing the (expensive) agent turn.
 */
@Component
public class MeetingIngestLedger {

    private final JdbcTemplate jdbcTemplate;

    public MeetingIngestLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Atomically claim a meeting for ingestion. Returns {@code true} if this call
     * inserted the row (caller should proceed to enrich), {@code false} if the
     * meeting was already recorded (caller should skip). Relies on the PRIMARY KEY
     * on {@code meeting_id} + {@code INSERT OR IGNORE} for a race-free check-and-set.
     */
    public boolean claim(String meetingId, String slug, String title, String startedAt, String source) {
        int rows = jdbcTemplate.update(
                "INSERT OR IGNORE INTO meetings_ingested (meeting_id, slug, title, started_at, source) "
                        + "VALUES (?, ?, ?, ?, ?)",
                meetingId, slug, title, startedAt, source);
        return rows > 0;
    }

    /**
     * Release a previously-claimed meeting so it can be retried — call this when
     * enrichment fails after the claim, otherwise a failed ingest leaves the
     * meeting marked done and every later attempt skips it.
     */
    public void release(String meetingId) {
        jdbcTemplate.update("DELETE FROM meetings_ingested WHERE meeting_id = ?", meetingId);
    }

    public boolean isIngested(String meetingId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM meetings_ingested WHERE meeting_id = ?", Integer.class, meetingId);
        return count != null && count > 0;
    }
}
