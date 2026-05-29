---
name: meeting-ingest
description: >
  Bring meetings recorded by the MeetingNotes app into Herald's memory. Use
  WHENEVER the user mentions meeting notes, meeting summaries, meeting
  transcripts, "my meetings", what was said/discussed/decided in a meeting, or
  asks to bring / save / import / catch up on meetings into memory — e.g. "bring
  yesterday's meeting notes into memory", "summarize today's meetings", "save my
  meetings". This is the MeetingNotes desktop app (recorded meeting content), NOT
  the calendar: the calendar holds scheduled events, this skill holds what
  actually happened in a meeting. If a request is about meeting notes/summaries,
  prefer this skill over google-calendar. Also fires automatically on the
  meeting.completed webhook and the daily catch-up.
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

## Ad-hoc invocation (user asked directly)

If you were **not** handed a meeting — e.g. the user said "bring yesterday's
meeting notes into memory" or "summarize today's meetings" — you have to find
them yourself before doing the steps below. Resolve the date (today / yesterday
in the user's timezone → `YYYY-MM-DD`), then discover that day's meetings from
MeetingNotes' own catalog with the `shell` tool:

```bash
sqlite3 "$HOME/Documents/MeetingNotes/db.sqlite" \
  "SELECT slug, title, started_at FROM meetings \
   WHERE date(started_at)='YYYY-MM-DD' AND status='done' AND deleted_at IS NULL \
   ORDER BY started_at;"
```

For each returned slug, the summary and action items live on disk:

```bash
cat "$HOME/Documents/MeetingNotes/meetings/<slug>/summary.md"
cat "$HOME/Documents/MeetingNotes/meetings/<slug>/action-items.json"
```

(Meetings with status other than `done` aren't finished processing — skip them
and tell the user they're not ready yet.) Then run the steps below **once per
meeting**. If there were no `done` meetings that day, just say so — don't invent
one.

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
