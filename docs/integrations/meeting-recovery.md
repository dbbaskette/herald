# MeetingNotes recovery and delivery

The bridge is opt-in. Set `HERALD_MEETINGNOTES_ENABLED=true` (or
`herald.meetingnotes.enabled=true`) to register the webhook API, catalog,
worker, recovery poll, and catch-up schedule. It defaults to `false`. Disabling
it unregisters those components without deleting queued work; re-enabling it
resumes recovery. Persistence must also be enabled for durable claims.

Completed webhook payloads are durably stored in Herald's SQLite database before
HTTP 202 is returned. Completion webhooks without a nonblank summary are rejected
with HTTP 400 before enqueue; incomplete catalog records are skipped so later
completed output can still claim the same meeting ID. Webhook, daily catch-up and manual backfill share one
sequential worker per Herald process, so a local model processes one meeting at a
time. Catch-up scans all non-deleted completed catalog entries, including earlier
days and meetings that finished processing late. The source catalog stays read-only.

The `meeting_ingest_jobs` queue records `pending`, `running`, `succeeded` or
`failed`, attempts, errors and the original payload. A running job has a two-minute
lease renewed every 30 seconds. Every worker checkpoint is fenced by its unique
lease token. The recovery poll (15 seconds, first run five seconds after startup)
reclaims expired leases and pending jobs, including webhook-only payloads, without
requiring MeetingNotes files. Failed jobs remain visible for deliberate retry;
repeated delivery of a webhook does not silently repeat failed external actions.

Settings → Meetings shows per-meeting outcomes and counts, and Retry on failed
jobs. A newly submitted backfill filters progress to the exact returned meeting
IDs. “Queued” is an acknowledgement, never a success count. Succeeded means the
memory note was verified and all enabled downstream steps returned successfully;
a failed reminder or recap leaves the job failed even when its memory note exists.

Before recording enrichment success, Herald verifies an actual Markdown note in
the configured memories directory or Obsidian vault containing an exact
`Source: <MeetingNotes id>` line and the complete original summary. A model's
claim or marker alone is insufficient. This verifies durable content, not the
semantic quality of its entity/project links. Existing meeting note layout,
verbatim summary, index, and enrichment skills remain in use.

## Independent delivery controls

- `HERALD_MEETINGNOTES_RECAP_ENABLED` / `herald.meetingnotes.recap-enabled`
  (default `true`): send Telegram recap when a MessageSender is available.
- `HERALD_MEETINGNOTES_REMINDERS_ENABLED` / `herald.meetingnotes.reminders-enabled`
  (default `true`): Java worker creates action items owned by Dan, me, unassigned,
  or no owner, using the existing Reminders tool. Other owners are skipped.

The automated agent turn is told to file/enrich only; Java owns downstream
fan-out and stores a checkpoint per successful reminder and recap. Disabled
steps are skipped. Changing a toggle does not replay already succeeded jobs.
Console progress is available regardless of recap settings. Missing/unavailable
Reminders causes a visible failure when enabled; disable it on unsupported hosts
and retry to complete the job without Reminders.

## Recovery limits

The former `meetings_ingested` table held claims without success evidence.
When a historical claim's payload is encountered in catch-up or webhook,
it becomes a failed job labelled “Legacy claim”; it is not counted as succeeded
or automatically replayed. Review the existing note and reminders before Retry.
Herald cannot retroactively prove whether old external actions were delivered.

An enrichment checkpoint prevents downstream retries from re-running the agent;
per-action checkpoints prevent replay of acknowledged reminders. Telegram recap
uses strict delivery: every chunk must receive a successful Telegram API response
before the recap checkpoint is written; terminal rejection, interruption or retry
exhaustion leaves the job failed. Existing non-meeting senders keep their previous
best-effort behavior. There remains
an unavoidable window between an external write and committing its checkpoint:
a crash then may duplicate an effect on retry. Apple Reminders and Telegram offer no shared transaction with Herald or
end-to-end idempotency keys. A Telegram API acknowledgement confirms acceptance,
not that the recipient read the message. Reminder notes carry the meeting ID/action index to help identify such
duplicates. A long process pause that expires a lease can also leave an old model
tool call in flight; fencing prevents it committing queue state but cannot undo
external tool effects. Run one active Herald instance against a vault. The agent
checks Source IDs and reuses notes/index entries on retries; exactly-once
external delivery is not claimed.

Tests use temporary SQLite catalogs, temporary Markdown notes and mocked agent,
Reminders and message transports. They do not use personal meetings or live
integrations.
