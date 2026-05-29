package com.herald.meetings;

import com.herald.agent.AgentService;
import com.herald.agent.MessageSender;
import com.herald.api.ChatNotificationsHub;
import com.herald.config.HeraldLimits;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

/**
 * Shared enrichment path for completed MeetingNotes meetings, used by both the
 * real-time webhook ({@code MeetingsController}) and the daily date-query
 * backstop ({@link MeetingCatchupJob}). Claims the meeting in the dedup ledger,
 * runs the {@code meeting-ingest} skill on a background virtual thread, and
 * delivers the resulting digest to Telegram and the web console.
 */
@Service
public class MeetingIngestService {

    private static final Logger log = LoggerFactory.getLogger(MeetingIngestService.class);

    private final AgentService agentService;
    private final MeetingIngestLedger ledger;
    private final ChatNotificationsHub notificationsHub;
    private final ChatMemory chatMemory;
    private final MessageSender messageSender;

    private final ExecutorService backgroundExecutor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("meeting-ingest-", 0).factory());

    public MeetingIngestService(AgentService agentService,
                                MeetingIngestLedger ledger,
                                ChatNotificationsHub notificationsHub,
                                ChatMemory chatMemory,
                                Optional<MessageSender> messageSender) {
        this.agentService = agentService;
        this.ledger = ledger;
        this.notificationsHub = notificationsHub;
        this.chatMemory = chatMemory;
        this.messageSender = messageSender.orElse(null);
    }

    /**
     * Claim the meeting and, if it hasn't been ingested before, enrich it on a
     * background thread. Returns {@code true} if newly claimed (enrichment
     * dispatched), {@code false} if it was already ingested (no-op). The dedup is
     * shared across sources, so a meeting that arrives via both the webhook and a
     * catch-up run is enriched exactly once.
     */
    public boolean claimAndIngest(MeetingDigest meeting, String source) {
        boolean claimed = ledger.claim(
                meeting.id(), meeting.slug(), meeting.title(), meeting.startedAt(), source);
        if (!claimed) {
            log.info("Meeting '{}' ({}) already ingested — skipping ({})", meeting.title(), meeting.id(), source);
            return false;
        }
        log.info("Enriching meeting '{}' ({}) via {}", meeting.title(), meeting.id(), source);
        backgroundExecutor.submit(() -> runIngestTurn(meeting));
        return true;
    }

    /**
     * Backfill a batch of meetings <b>sequentially</b> — one small agent turn at a
     * time, each completing before the next starts. This is deliberately not the
     * concurrent {@link #claimAndIngest} path: a local model (LM Studio / Ollama)
     * can OOM or stall if asked to process several meetings at once, and one big
     * multi-meeting turn is even worse. Looping in Java with focused per-meeting
     * turns keeps each request small and the model happy, and the ledger ensures
     * meetings already ingested are skipped. Runs on a single background thread so
     * the HTTP trigger returns immediately; progress is delivered as it goes.
     *
     * @return the number of meetings that will be processed (not already ingested)
     */
    public int backfillAsync(List<MeetingDigest> meetings, String source) {
        List<MeetingDigest> todo = new java.util.ArrayList<>();
        for (MeetingDigest m : meetings) {
            if (ledger.claim(m.id(), m.slug(), m.title(), m.startedAt(), source)) {
                todo.add(m);
            }
        }
        int skipped = meetings.size() - todo.size();
        if (todo.isEmpty()) {
            log.info("Backfill: nothing to do ({} already ingested)", skipped);
            return 0;
        }
        backgroundExecutor.submit(() -> {
            log.info("Backfill starting: {} meeting(s) to enrich, {} already ingested", todo.size(), skipped);
            if (messageSender != null) {
                messageSender.sendMessage("📥 Bringing in " + todo.size()
                        + " meeting(s), one at a time…");
            }
            int done = 0;
            for (MeetingDigest m : todo) {
                runIngestTurn(m); // blocking — strictly one meeting at a time
                done++;
            }
            log.info("Backfill complete: {} enriched, {} already ingested", done, skipped);
            if (messageSender != null) {
                messageSender.sendMessage("✅ Backfill done — " + done + " meeting(s) brought into memory"
                        + (skipped > 0 ? " (" + skipped + " were already there)." : "."));
            }
        });
        return todo.size();
    }

    private void runIngestTurn(MeetingDigest meeting) {
        String conversationId = "meeting-" + meeting.id();
        try {
            String reply = agentService.chat(buildPrompt(meeting), conversationId);
            if (reply != null && !reply.isBlank()) {
                if (messageSender != null) {
                    messageSender.sendMessage(reply);
                }
                notificationsHub.publish(HeraldLimits.WEB_CONVERSATION_ID, "message", reply);
            }
            log.info("Enriched meeting '{}' ({})", meeting.title(), meeting.id());
        } catch (Exception e) {
            log.warn("Meeting enrichment failed for '{}' ({}): {}",
                    meeting.title(), meeting.id(), e.getMessage(), e);
            if (messageSender != null) {
                try {
                    messageSender.sendMessage(
                            "Meeting enrichment failed for '" + meeting.title() + "': " + e.getMessage());
                } catch (Exception ignored) {
                    // best-effort notification
                }
            }
        } finally {
            chatMemory.clear(conversationId);
        }
    }

    /**
     * Render a meeting into a self-contained instruction. The enrichment behavior
     * (what to save, which action items become reminders) lives in the
     * {@code meeting-ingest} skill — this just hands over the data.
     */
    static String buildPrompt(MeetingDigest m) {
        StringBuilder sb = new StringBuilder();
        sb.append("A meeting just finished processing in MeetingNotes. ")
          .append("Use the meeting-ingest skill to process it.\n\n");
        sb.append("## Meeting\n");
        sb.append("- Title: ").append(orDash(m.title())).append('\n');
        if (m.startedAt() != null && !m.startedAt().isBlank()) {
            sb.append("- Started: ").append(m.startedAt()).append('\n');
        }
        if (m.durationS() != null) {
            sb.append("- Duration: ").append(m.durationS() / 60).append(" min\n");
        }
        if (m.attendees() != null && !m.attendees().isEmpty()) {
            sb.append("- Attendees: ").append(String.join(", ", m.attendees())).append('\n');
        }
        sb.append("- MeetingNotes id: ").append(m.id()).append('\n');

        sb.append("\n## Summary\n");
        sb.append(m.summaryMarkdown() != null && !m.summaryMarkdown().isBlank()
                ? m.summaryMarkdown() : "_(no summary provided)_").append('\n');

        List<MeetingDigest.ActionItem> items = m.actionItems();
        if (items != null && !items.isEmpty()) {
            sb.append("\n## Action items\n");
            for (MeetingDigest.ActionItem ai : items) {
                sb.append("- ").append(orDash(ai.text()));
                if (ai.owner() != null && !ai.owner().isBlank()) sb.append(" — ").append(ai.owner());
                if (ai.dueDate() != null && !ai.dueDate().isBlank()) {
                    sb.append(" (due ").append(ai.dueDate()).append(')');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
