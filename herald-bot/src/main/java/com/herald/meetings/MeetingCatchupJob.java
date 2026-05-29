package com.herald.meetings;

import com.herald.config.HeraldConfig;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily safety net for the MeetingNotes webhook. If Herald was down (or the
 * webhook misfired) when a meeting completed, the real-time push is lost — this
 * job re-queries the day's meetings from {@link MeetingNotesCatalog} and ingests
 * any that aren't already in the dedup ledger. When every meeting arrived via
 * webhook as intended, this is a silent no-op.
 *
 * <p>Schedule via {@code herald.meetingnotes.catchup-cron} (default 6pm daily);
 * set it to {@code -} to disable.</p>
 */
@Component
public class MeetingCatchupJob {

    private static final Logger log = LoggerFactory.getLogger(MeetingCatchupJob.class);

    private final MeetingNotesCatalog catalog;
    private final MeetingIngestService ingestService;
    private final ZoneId timezone;

    public MeetingCatchupJob(MeetingNotesCatalog catalog,
                             MeetingIngestService ingestService,
                             HeraldConfig config) {
        this.catalog = catalog;
        this.ingestService = ingestService;
        this.timezone = ZoneId.of(config.cronTimezone());
    }

    @Scheduled(cron = "${herald.meetingnotes.catchup-cron:0 0 18 * * *}",
               zone = "${herald.cron.timezone:America/New_York}")
    public void run() {
        LocalDate today = LocalDate.now(timezone);
        List<MeetingDigest> meetings = catalog.findByDate(today);
        if (meetings.isEmpty()) {
            return;
        }
        int caught = 0;
        for (MeetingDigest m : meetings) {
            // Only completed meetings have a summary worth enriching; skip ones
            // still being processed. The ledger drops anything already ingested.
            if (!"done".equalsIgnoreCase(m.status()) || m.summaryMarkdown() == null) {
                continue;
            }
            if (ingestService.claimAndIngest(m, "catchup")) {
                caught++;
            }
        }
        if (caught > 0) {
            log.info("Meeting catch-up enriched {} missed meeting(s) for {}", caught, today);
        }
    }
}
