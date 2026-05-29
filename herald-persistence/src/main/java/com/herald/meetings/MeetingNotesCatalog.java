package com.herald.meetings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.herald.config.HeraldConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only window onto the MeetingNotes desktop app's durable output. This is
 * the backstop for the real-time webhook: given a date it returns every meeting
 * MeetingNotes recorded that day, pulling metadata from its SQLite catalog and
 * the summary / action-item text from the per-meeting folders on disk.
 *
 * <p>We read MeetingNotes' own files rather than calling a live API because the
 * files persist whether or not the app is running — an HTTP endpoint would only
 * answer while the Electron app is open. Access is strictly read-only; Herald
 * never writes to the MeetingNotes database.</p>
 */
@Component
public class MeetingNotesCatalog {

    private static final Logger log = LoggerFactory.getLogger(MeetingNotesCatalog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HeraldConfig config;

    public MeetingNotesCatalog(HeraldConfig config) {
        this.config = config;
    }

    /**
     * Every non-deleted meeting whose {@code started_at} falls on {@code date}.
     * Each result carries its summary and action items if MeetingNotes has
     * finished processing it (status {@code done}); meetings still in progress
     * come back with a null summary. Returns an empty list — never throws — when
     * MeetingNotes isn't installed or its database is missing.
     */
    public List<MeetingDigest> findByDate(LocalDate date) {
        return findByDateRange(date, date);
    }

    /**
     * Every non-deleted meeting whose {@code started_at} date falls within
     * {@code [from, to]} inclusive. Same enrichment as {@link #findByDate} — summary
     * and action items attached when present. Returns an empty list (never throws)
     * when MeetingNotes isn't installed or its database is missing.
     */
    public List<MeetingDigest> findByDateRange(LocalDate from, LocalDate to) {
        Path dbPath = expand(config.meetingNotesDbPath());
        if (!Files.exists(dbPath)) {
            log.debug("MeetingNotes catalog not found at {} — returning no meetings", dbPath);
            return List.of();
        }

        try {
            JdbcTemplate jdbc = readOnlyTemplate(dbPath);
            List<Row> rows = jdbc.query(
                    "SELECT id, slug, title, started_at, duration_s, status FROM meetings "
                            + "WHERE date(started_at) BETWEEN ? AND ? AND deleted_at IS NULL ORDER BY started_at",
                    (rs, n) -> {
                        // duration_s has INTEGER affinity but SQLite stores it as a
                        // float (e.g. 1827.306667), so read defensively as a double.
                        Object durRaw = rs.getObject("duration_s");
                        Integer dur = durRaw == null ? null : (int) Math.round(rs.getDouble("duration_s"));
                        return new Row(
                                rs.getString("id"), rs.getString("slug"), rs.getString("title"),
                                rs.getString("started_at"), dur, rs.getString("status"));
                    },
                    from.toString(), to.toString());

            List<MeetingDigest> out = new ArrayList<>(rows.size());
            for (Row r : rows) {
                out.add(new MeetingDigest(
                        r.id(), r.slug(), r.title(), r.startedAt(), r.durationS(), r.status(),
                        List.of(), readSummary(r.slug()), readActionItems(r.slug())));
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Failed to read MeetingNotes catalog at {}: {}", dbPath, e.getMessage());
            return List.of();
        }
    }

    private JdbcTemplate readOnlyTemplate(Path dbPath) {
        org.sqlite.SQLiteDataSource ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath);
        ds.setReadOnly(true);
        return new JdbcTemplate(ds);
    }

    private String readSummary(String slug) {
        Path p = meetingDir(slug).resolve("summary.md");
        if (!Files.exists(p)) return null;
        try {
            return Files.readString(p);
        } catch (Exception e) {
            log.debug("Could not read summary for {}: {}", slug, e.getMessage());
            return null;
        }
    }

    private List<MeetingDigest.ActionItem> readActionItems(String slug) {
        Path p = meetingDir(slug).resolve("action-items.json");
        if (!Files.exists(p)) return List.of();
        try {
            List<RawActionItem> raw = MAPPER.readValue(Files.readString(p), new TypeReference<>() {});
            List<MeetingDigest.ActionItem> items = new ArrayList<>(raw.size());
            for (RawActionItem r : raw) {
                items.add(new MeetingDigest.ActionItem(r.text(), r.owner(), r.due_date()));
            }
            return items;
        } catch (Exception e) {
            log.debug("Could not parse action items for {}: {}", slug, e.getMessage());
            return List.of();
        }
    }

    private Path meetingDir(String slug) {
        return expand(config.meetingNotesDir()).resolve("meetings").resolve(slug);
    }

    private static Path expand(String raw) {
        if (raw.startsWith("~")) {
            return Path.of(System.getProperty("user.home") + raw.substring(1));
        }
        return Path.of(raw);
    }

    private record Row(String id, String slug, String title, String startedAt,
                       Integer durationS, String status) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record RawActionItem(String text, String owner, String due_date) {}
}
