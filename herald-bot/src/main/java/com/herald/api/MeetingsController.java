package com.herald.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.herald.config.HeraldConfig;
import com.herald.meetings.MeetingDigest;
import com.herald.meetings.MeetingIngestService;
import com.herald.meetings.MeetingNotesCatalog;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MeetingNotes integration endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/meetings/ingest} — webhook receiver. Configure
 *       MeetingNotes → Settings → Webhook to POST the {@code compact} template
 *       here; each {@code meeting.completed} event is deduped and handed to the
 *       agent for enrichment via {@link MeetingIngestService}.</li>
 *   <li>{@code GET /api/meetings?date=YYYY-MM-DD} — the date-query backstop.
 *       Returns every meeting MeetingNotes recorded that day (summary + action
 *       items included once processing finishes), read straight from the app's
 *       durable output. Defaults to today.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/meetings")
public class MeetingsController {

    private static final Logger log = LoggerFactory.getLogger(MeetingsController.class);

    private final MeetingIngestService ingestService;
    private final MeetingNotesCatalog catalog;
    private final ZoneId timezone;

    public MeetingsController(MeetingIngestService ingestService,
                              MeetingNotesCatalog catalog,
                              HeraldConfig config) {
        this.ingestService = ingestService;
        this.catalog = catalog;
        this.timezone = ZoneId.of(config.cronTimezone());
    }

    @PostMapping(value = "/ingest",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestAck> ingest(@RequestBody IngestPayload payload) {
        if (payload == null || payload.meeting() == null
                || payload.meeting().id() == null || payload.meeting().id().isBlank()) {
            return ResponseEntity.badRequest().body(new IngestAck("invalid", null));
        }
        // Only act on completion events; ignore anything else the exporter might send.
        if (payload.event() != null && !"meeting.completed".equals(payload.event())) {
            return ResponseEntity.ok(new IngestAck("ignored", payload.meeting().id()));
        }

        boolean claimed = ingestService.claimAndIngest(payload.toDigest(), "webhook");
        if (!claimed) {
            return ResponseEntity.ok(new IngestAck("duplicate", payload.meeting().id()));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new IngestAck("accepted", payload.meeting().id()));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<MeetingDigest> byDate(@RequestParam(name = "date", required = false) String date) {
        LocalDate target = parseDateOrToday(date);
        List<MeetingDigest> meetings = catalog.findByDate(target);
        log.info("Date query {} → {} meeting(s)", target, meetings.size());
        return meetings;
    }

    /**
     * Deterministic bulk backfill: enrich every completed meeting in a date range,
     * <b>one small agent turn at a time</b> (server-side loop), rather than asking
     * the model to handle a whole batch in one turn. Gentle on local models, and
     * the ledger skips meetings already in memory. Returns immediately (202);
     * progress is delivered to Telegram / the console as each meeting lands.
     *
     * <p>Defaults to the last 7 days. Override with {@code ?from=&to=} (YYYY-MM-DD)
     * or {@code ?days=N}.</p>
     */
    @PostMapping(value = "/backfill", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BackfillAck> backfill(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "days", required = false) Integer days) {
        LocalDate end = parseDateOr(to, LocalDate.now(timezone));
        LocalDate start = (from != null && !from.isBlank())
                ? parseDateOr(from, end)
                : end.minusDays((days != null && days > 0 ? days : 7) - 1L);

        List<MeetingDigest> all = catalog.findByDateRange(start, end);
        List<MeetingDigest> ready = all.stream()
                .filter(m -> "done".equalsIgnoreCase(m.status()) && m.summaryMarkdown() != null)
                .toList();
        int queued = ingestService.backfillAsync(ready, "backfill");
        log.info("Backfill {}..{}: {} completed meeting(s), {} queued", start, end, ready.size(), queued);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new BackfillAck(start.toString(), end.toString(), ready.size(), queued));
    }

    private LocalDate parseDateOrToday(String date) {
        return parseDateOr(date, LocalDate.now(timezone));
    }

    private LocalDate parseDateOr(String date, LocalDate fallback) {
        if (date == null || date.isBlank()) return fallback;
        try {
            return LocalDate.parse(date.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IngestPayload(
            String event,
            Meeting meeting,
            @JsonProperty("summary_markdown") String summaryMarkdown,
            @JsonProperty("action_items") List<ActionItem> actionItems) {

        MeetingDigest toDigest() {
            List<MeetingDigest.ActionItem> items = actionItems == null ? List.of()
                    : actionItems.stream()
                        .map(a -> new MeetingDigest.ActionItem(a.text(), a.owner(), a.dueDate()))
                        .toList();
            return new MeetingDigest(
                    meeting.id(), meeting.slug(), meeting.title(), meeting.startedAt(),
                    meeting.durationS() == null ? null : meeting.durationS().intValue(),
                    "done",
                    meeting.attendees() == null ? List.of() : meeting.attendees(),
                    summaryMarkdown, items);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meeting(
            String id,
            String slug,
            String title,
            @JsonProperty("started_at") String startedAt,
            @JsonProperty("duration_s") Long durationS,
            List<String> attendees) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActionItem(
            String text,
            String owner,
            @JsonProperty("due_date") String dueDate) {}

    public record IngestAck(String status, String meetingId) {}

    /** Result of kicking off a backfill: the resolved range, completed meetings found,
     *  and how many were queued for enrichment (the rest were already in memory). */
    public record BackfillAck(String from, String to, int found, int queued) {}
}
