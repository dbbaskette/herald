package com.herald.meetings;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Durable queue. Every update by a worker is fenced by its unique lease token. */
@Component
public class MeetingIngestLedger {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @Autowired
    public MeetingIngestLedger(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
    MeetingIngestLedger(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public boolean enqueue(MeetingDigest m, String source) {
        if (m == null || !m.readyForIngest()) return false;
        try {
            boolean legacy = jdbc.queryForObject("SELECT count(*) FROM meetings_ingested WHERE meeting_id=?", Integer.class,m.id())>0;
            int inserted = jdbc.update("INSERT OR IGNORE INTO meeting_ingest_jobs "
                    + "(meeting_id,title,payload,source,state,error,updated_at) VALUES (?,?,?,?,?,?,?)",
                    m.id(), m.title(), JSON.writeValueAsString(m), source, legacy?"failed":"pending",
                    legacy?"Legacy claim has no success evidence. Review existing note and reminders before explicit Retry.":null, clock.millis());
            return inserted>0 && !legacy;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }

    public List<String> recoverable() {
        return jdbc.queryForList("SELECT meeting_id FROM meeting_ingest_jobs WHERE state='pending' "
                + "OR (state='running' AND lease_until < ?) ORDER BY updated_at", String.class, clock.millis());
    }

    public Claim acquire(String id, long leaseMillis) {
        String token = UUID.randomUUID().toString();
        if (jdbc.update("UPDATE meeting_ingest_jobs SET state='running', lease_token=?, lease_until=?, "
                + "attempts=attempts+1, error=NULL, updated_at=? WHERE meeting_id=? "
                + "AND (state='pending' OR (state='running' AND lease_until < ?))",
                token, clock.millis()+leaseMillis, clock.millis(), id, clock.millis()) == 0) return null;
        return jdbc.queryForObject("SELECT payload,reply FROM meeting_ingest_jobs WHERE meeting_id=?",
                (rs,n) -> {
                    try { return new Claim(id, token, JSON.readValue(rs.getString(1), MeetingDigest.class), rs.getString(2)); }
                    catch (Exception e) { throw new IllegalStateException("Invalid durable meeting payload", e); }
                }, id);
    }
    public boolean renew(Claim c, long leaseMillis) {
        return jdbc.update("UPDATE meeting_ingest_jobs SET lease_until=? WHERE meeting_id=? AND lease_token=? "
                + "AND state='running' AND lease_until>=?", clock.millis()+leaseMillis, c.id(), c.token(), clock.millis()) == 1;
    }
    public void checkpoint(Claim c, String reply) {
        fenced(jdbc.update("UPDATE meeting_ingest_jobs SET reply=? WHERE meeting_id=? AND lease_token=? AND state='running' AND lease_until>=?",
                reply, c.id(), c.token(), clock.millis()));
    }
    public boolean effectDone(Claim c, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM meeting_ingest_effects WHERE meeting_id=? AND effect_key=?",
                Integer.class, c.id(), key) > 0;
    }
    public void recordEffect(Claim c, String key) {
        fenced(jdbc.update("INSERT OR IGNORE INTO meeting_ingest_effects(meeting_id,effect_key) "
                + "SELECT meeting_id,? FROM meeting_ingest_jobs WHERE meeting_id=? AND lease_token=? AND state='running' AND lease_until>=?",
                key, c.id(), c.token(), clock.millis()));
    }
    public void finish(Claim c, String error) {
        fenced(jdbc.update("UPDATE meeting_ingest_jobs SET state=?,error=?,lease_token=NULL,lease_until=NULL,updated_at=? "
                + "WHERE meeting_id=? AND lease_token=? AND state='running' AND lease_until>=?",
                error == null ? "succeeded" : "failed", error, clock.millis(), c.id(), c.token(), clock.millis()));
    }
    public boolean retry(String id) {
        return jdbc.update("UPDATE meeting_ingest_jobs SET state='pending',updated_at=? WHERE meeting_id=? AND state='failed'",
                clock.millis(), id) == 1;
    }
    public List<Progress> progress() {
        return jdbc.query("SELECT meeting_id,title,state,attempts,error,updated_at FROM meeting_ingest_jobs ORDER BY updated_at DESC",
                (rs,n) -> new Progress(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getString(5),rs.getLong(6)));
    }
    public boolean isIngested(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM meeting_ingest_jobs WHERE meeting_id=? AND state='succeeded'", Integer.class,id)>0;
    }
    private void fenced(int rows) { if (rows != 1) throw new IllegalStateException("Meeting lease lost"); }
    public record Claim(String id, String token, MeetingDigest meeting, String reply) {}
    public record Progress(String meetingId, String title, String state, int attempts, String error, long updatedAt) {}
}
