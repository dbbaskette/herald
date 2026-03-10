---
name: google-calendar
description: >
  Manages Google Calendar via gws CLI — list, create, delete, and search events,
  find free time slots. Use when asked about calendar, schedule, meetings,
  or availability.
---

# Google Calendar Skill

Manage Google Calendar using the `gws` CLI tool. All commands use `--format json` for parseable output and `--calendar-id primary` unless the user specifies a different calendar.

## Prerequisites

The `gws` CLI must be installed and authenticated. Test with:

```bash
gws calendar events list --calendar-id primary --time-min "$(date -u +%Y-%m-%dT00:00:00Z)" --time-max "$(date -u +%Y-%m-%dT23:59:59Z)" --format json
```

If this fails with an auth error, tell the user: "Google Workspace CLI (`gws`) is not configured. Run `gws auth login` to authenticate."

## Date/Time Handling

- All timestamps must be in ISO 8601 format with timezone offset: `YYYY-MM-DDTHH:MM:SS-05:00`
- The user's timezone is available from the system context. Convert relative references ("today", "tomorrow", "Thursday") to absolute dates using the current date/time.
- Default event duration is 30 minutes if no end time is specified.
- For "morning" use 09:00, "afternoon" use 13:00–17:00, "end of day" use 17:00, "lunch" use 12:00.

## Commands

### List Events

Retrieve events for a time range:

```bash
gws calendar events list --calendar-id primary --time-min "<ISO_START>" --time-max "<ISO_END>" --format json
```

- For "today": use today's date at 00:00:00 as `--time-min` and 23:59:59 as `--time-max`
- For "this week": use Monday 00:00:00 through Sunday 23:59:59
- Always include timezone offset in timestamps (e.g., `2026-03-09T00:00:00-05:00`)

**Use for:** "What's on my calendar today?", "Show my schedule for this week", "What meetings do I have tomorrow?"

### Create Event

```bash
gws calendar events create --calendar-id primary --summary "<TITLE>" --start "<ISO_START>" --end "<ISO_END>" --format json
```

- Always confirm the event details with the user before creating.
- Parse natural language times: "tomorrow at 2pm" → next day at 14:00 in user's timezone.
- If no end time given, default to 30 minutes after start.
- If attendees are mentioned but no email is available, note this and create the event without attendees.

**Use for:** "Schedule a meeting with Alice tomorrow at 2pm", "Add a dentist appointment Friday at 10am", "Block off 1-3pm for focus time"

### Delete Event

```bash
gws calendar events delete --calendar-id primary --event-id <EVENT_ID>
```

- First list events to find the matching event ID.
- Confirm with the user before deleting: "I found '**<event summary>**' at <time>. Delete it?"
- If the match is ambiguous (multiple events with similar names), ask which one.

**Use for:** "Cancel my 3pm meeting", "Remove the standup from tomorrow"

### Search Events

```bash
gws calendar events list --calendar-id primary --q "<SEARCH_TERM>" --time-min "<ISO_START>" --time-max "<ISO_END>" --format json
```

- Use `--q` for keyword search across event summaries and descriptions.
- Always include a reasonable time range (default: next 30 days) to scope the search.

**Use for:** "Do I have any meetings with Alice?", "Find my dentist appointment"

### Find Free Time

There is no dedicated free-time command. To find availability:

1. List events for the requested date range.
2. Identify gaps between events during working hours (09:00–17:00 in the user's timezone).
3. Report available time slots with durations.

**Use for:** "Am I free Thursday afternoon?", "When can I schedule a 1-hour meeting this week?", "Find me a free slot tomorrow"

## Parsing JSON Output

The JSON output contains an `items` array. Each event has:
- `id` — event ID (needed for delete)
- `summary` — event title
- `start.dateTime` or `start.date` — start time (dateTime for timed events, date for all-day)
- `end.dateTime` or `end.date` — end time
- `attendees` — list of attendees
- `location` — event location
- `status` — confirmed, tentative, or cancelled

## Response Formatting

Format responses as clean Telegram-friendly messages (Markdown):

- **Event list:** `• HH:MM – HH:MM  **Event Title**` (one per line, sorted by start time). Include location if set.
- **Empty calendar:** "Your calendar is clear for today." or "No events scheduled for <date range>."
- **Created event:** "Created: **<Title>** on <date> at <time>."
- **Deleted event:** "Deleted: **<Title>**."
- **Free time:** List available slots as `• HH:MM – HH:MM (X hours available)`

## Error Handling

| Error | Response |
|-------|----------|
| `gws: command not found` | "The `gws` CLI is not installed. Install it with `brew install gws` or see the gws documentation." |
| Authentication failure / token expired | "Google auth has expired. Run `gws auth login` to re-authenticate." |
| No events found | "Your calendar is clear for that time range." |
| Invalid date/time from user | Ask the user to clarify: "I couldn't parse that date. Could you specify it like 'March 10 at 2pm'?" |
| Network error | "Couldn't reach Google Calendar. Check your internet connection." |
| Event not found for deletion | "I couldn't find a matching event. Can you be more specific about which event to cancel?" |

## Example Interactions

**"What's on my calendar today?"**
→ List events with time-min = start of today, time-max = end of today. Format as bullet list.

**"Schedule a meeting with Alice tomorrow at 2pm"**
→ Parse "tomorrow at 2pm" to ISO timestamp. Confirm: "Create **Meeting with Alice** tomorrow (Mar 10) 2:00–2:30 PM?" Then create.

**"Am I free Thursday afternoon?"**
→ List events for Thursday 13:00–17:00. Report gaps or "You're free all Thursday afternoon."

**"Cancel my 3pm meeting"**
→ List today's events, find the one at/near 3pm, confirm with user, then delete by event ID.

**"What do I have this week?"**
→ List events Monday through Friday. Group by day for readability.
