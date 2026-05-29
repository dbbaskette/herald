---
name: meeting-ingest
description: >
  Process a completed meeting from the MeetingNotes desktop app — save a durable
  memory note, turn action items into Apple Reminders, and send a short digest.
  Invoked automatically when a meeting.completed webhook arrives, and by the daily
  meeting-catchup backstop. Also use it when the user asks to "process",
  "summarize", or "catch up on" recent meetings.
---

# Meeting Ingest Skill

MeetingNotes records and transcribes meetings on this Mac, then summarizes them
with a local LLM. When a meeting finishes it posts a `meeting.completed` webhook
to Herald, which hands you the structured result. Your job is to file it where
Dan will find it later and surface anything that needs action.

You are given (as the turn prompt):

- **Title**, **Started** timestamp, **Duration**, **Attendees**, and the
  **MeetingNotes id**.
- A **Summary** (markdown) produced by MeetingNotes.
- Zero or more **Action items**, each with optional owner and due date.

The transcript is intentionally **not** included — work from the summary.

## What to do

Do these in one turn, then reply with the digest. Keep it tight; this is an
unattended pipeline, not a conversation.

### 1. Save a durable memory note

Capture the meeting so it's recallable weeks later. Use `memoryCreate` to write
a `project`-type note named after the meeting (kebab-case slug from the title +
date, e.g. `meeting-pm-marketing-sync-2026-05-28`). Include in the body:

- Title, date/time, attendees.
- A 2–4 bullet distillation of the summary — decisions, outcomes, and any
  numbers or names worth keeping. Do **not** paste the whole summary verbatim;
  compress it.
- The action items with their owners.
- The MeetingNotes id on a `Source:` line so a later catch-up can tell this
  meeting was already filed.

Then add a one-line pointer to `MEMORY.md` per the memory conventions.

If Dan keeps an Obsidian vault (the `obsidian` / `wiki-ingest` skills are
available and a vault path is configured), prefer filing the note there via
`wiki-ingest` so it lives alongside his other notes. Otherwise the file-memory
note above is enough — don't do both.

### 2. Turn action items into reminders

For each action item that is **Dan's** (owner is Dan, "me", "unassigned", or
blank), create an Apple Reminder via the `reminders` skill:

```
reminders_create("Reminders", "<action item text>", "<due date or null>", "Meeting: <title>")
```

- Skip items clearly owned by **someone else** — note them in the digest, but
  don't put another person's task on Dan's list.
- Pass the due date through only if the action item has one; otherwise `null`.
- If the `reminders` tools return an "unavailable" error (non-macOS, or CLI not
  installed), skip this step silently and just mention the action items in the
  digest.

Do **not** create calendar events here. Only schedule a follow-up meeting if the
summary explicitly says one was agreed — and if so, confirm with Dan first
rather than booking it unattended.

### 3. Reply with a short digest

This reply is delivered to Telegram and the web console, so make it skimmable:

```
📋 <Title> — <date>, <N> min
<one-line gist>

✅ Added N reminders:
• <item> (due …)
Tracking for others:
• <item> — <owner>
```

If there were no action items, drop the reminders block. Keep the whole digest
under ~10 lines.

## Guardrails

- **Idempotent by id.** Herald dedupes by MeetingNotes id before invoking you,
  so you won't normally see the same meeting twice. But if you're processing a
  batch (catch-up) and a memory note with that `Source:` id already exists, skip
  it — don't double-file or double-remind.
- **Don't invent action items.** Only act on items MeetingNotes extracted.
- **Compress, don't copy.** The memory note is a distillation, not a transcript
  dump. Long summaries get 4 bullets, not 40 lines.
- **Fail soft.** If memory or reminders are unavailable, still send the digest so
  Dan at least sees the meeting happened.

## Related

- `skills/reminders/SKILL.md` — action items → Apple Reminders.
- `skills/wiki-ingest/SKILL.md` — file the note into the Obsidian vault.
- `skills/google-calendar/SKILL.md` — only for explicitly-agreed follow-ups.
