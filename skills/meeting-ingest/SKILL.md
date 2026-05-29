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

If you were **not** handed a meeting — e.g. "bring yesterday's meeting notes into
memory", "summarize today's meetings", or **"save this week's meetings"** — you
have to find them yourself before doing the steps below.

**Step A — resolve the date range.** Most requests cover more than one day:
- "today" / "yesterday" → that single date.
- "this week" → Monday of the current week through today (inclusive).
- "last N days" / "this month" → the obvious range.
Compute the start (and end, default today) as `YYYY-MM-DD` in the user's timezone.

**Step B — list every matching meeting** with the `shell` tool. Use a range so a
multi-day request comes back in one query (single-day = same start and end):

```bash
sqlite3 "$HOME/Documents/MeetingNotes/db.sqlite" \
  "SELECT slug, title, date(started_at) AS d, status FROM meetings \
   WHERE date(started_at) BETWEEN 'START' AND 'END' AND deleted_at IS NULL \
   ORDER BY started_at;"
```

**Step C — process EVERY `done` meeting the query returned, not just the first.**
This is the most common failure: stopping after one. If the query returns four
meetings, you save four. For each one, read its summary and action items off disk:

```bash
cat "$HOME/Documents/MeetingNotes/meetings/<slug>/summary.md"
cat "$HOME/Documents/MeetingNotes/meetings/<slug>/action-items.json"
```

then run the steps below for it. Meetings with status other than `done` aren't
finished processing — skip them and say which ones aren't ready. Before saving a
meeting, check whether a memory note with its `Source:` id already exists (it may
have been filed earlier); if so, skip it rather than duplicating. If no `done`
meetings fall in the range, just say so — don't invent one.

Work through the full list meeting by meeting; the final digest must account for
**all** of them (saved, skipped-as-duplicate, or not-ready).

## What to do

Do these in one turn, then reply with the digest. Keep it tight; this is an
unattended pipeline, not a conversation.

**Actually call the tools — do not just describe what you would do.** "Bringing a
meeting into memory" means invoking `memoryCreate` and seeing it succeed. Listing
or summarizing a meeting in your reply is NOT saving it. Do not tell the user a
meeting was saved unless the `memoryCreate` (or `wiki-ingest`) call actually ran
and returned success this turn.

### 1. Save a durable memory note

Capture the meeting so it's recallable weeks later. Use `memoryCreate` to write
a `project`-type note. **Always save it under the `meetings/` folder** — pass the
path `meetings/<kebab-slug>` (slug from the title + date), e.g.
`meetings/meeting-pm-marketing-sync-2026-05-28`. Every meeting note lives in
`meetings/` so they stay together; do **not** write it to the vault root. Include in the body:

- A short header: title, date/time, attendees.
- The **full summary, verbatim** — copy the entire summary you were given into
  the note exactly as-is. MeetingNotes already distilled the transcript into this
  summary; do **not** shorten, paraphrase, or re-summarize it (that would be a
  summary of a summary and lose detail). The whole point of the note is to
  preserve the complete summary.
- The action items with their owners.
- The MeetingNotes id on a `Source:` line so a later catch-up can tell this
  meeting was already filed.

Then add a one-line pointer to `MEMORY.md` under the **`## Meetings`** section
(create that section if it doesn't exist yet) — all meeting notes are indexed
together there, never under Projects/References. Use `MemoryView` on `MEMORY.md`
first and skip if this meeting is already listed (don't duplicate the entry).

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

This reply is delivered to Telegram and the web console, so make it skimmable.
Open with a line that names where each meeting was actually saved — cite the note
path returned by `memoryCreate`, so "did it save?" is never a guess:

```
💾 Saved N meeting(s) to memory:
• <Title> (<date>) → <note path>
• <Title> (<date>) → <note path>
Skipped: <Title> (already saved) · <Title> (still processing)

📋 Per meeting, one line each:
• <Title> — <one-line gist> · ✅ <k> reminders added
```

List one `💾 Saved` line per meeting you actually filed, citing the path
`memoryCreate` returned. For a single meeting you can expand the gist a little;
for a week, keep each meeting to one line so the digest stays skimmable. If a
save failed, say so plainly — never report a meeting as saved when the tool call
didn't succeed.

## Guardrails

- **Idempotent by id.** Herald dedupes by MeetingNotes id before invoking you,
  so you won't normally see the same meeting twice. But if you're processing a
  batch (catch-up) and a memory note with that `Source:` id already exists, skip
  it — don't double-file or double-remind.
- **Don't invent action items.** Only act on items MeetingNotes extracted.
- **Preserve the full summary.** MeetingNotes already summarized the meeting —
  store that summary complete and verbatim. Don't re-summarize or trim it; a
  summary-of-a-summary loses the detail Dan wants to recall later. (Only the
  digest *reply* is short — the saved note keeps everything.)
- **Fail soft.** If memory or reminders are unavailable, still send the digest so
  Dan at least sees the meeting happened.

## Related

- `skills/reminders/SKILL.md` — action items → Apple Reminders.
- `skills/wiki-ingest/SKILL.md` — file the note into the Obsidian vault.
- `skills/google-calendar/SKILL.md` — only for explicitly-agreed follow-ups.
