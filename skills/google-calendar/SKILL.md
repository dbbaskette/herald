---
name: google-calendar
description: >
  Manages Google Calendar via gws CLI — list, create, delete, and search events,
  find free time slots. Use when asked about calendar, schedule, meetings,
  or availability.
---

# Google Calendar Skill

Manage Google Calendar using the `gws` CLI tool (Google Workspace CLI). All commands use `--format json` for parseable output and `calendarId: "primary"` unless the user specifies a different calendar.

## Prerequisites

The `gws` CLI must be installed and authenticated with Calendar scopes. Test with:

```bash
gws calendar events list --params '{"calendarId": "primary", "timeMin": "'$(date -u +%Y-%m-%dT00:00:00Z)'", "maxResults": 3}' --format json
```

If this fails with an auth error, tell the user: "Google Workspace CLI (`gws`) is not authenticated for Calendar. Run `source .env && gws auth login -s calendar` — see docs/gws-setup.md."

## Date/Time Handling

- All timestamps must be in RFC 3339 format: `YYYY-MM-DDTHH:MM:SS-05:00` (with timezone offset)
- The user's timezone is available from the system context. Convert relative references ("today", "tomorrow", "Thursday") to absolute dates.
- Default event duration is 30 minutes if no end time is specified.
- For "morning" use 09:00, "afternoon" use 13:00–17:00, "end of day" use 17:00, "lunch" use 12:00.

## Commands

### List Events

```bash
gws calendar events list --params '{"calendarId": "primary", "timeMin": "<RFC3339_START>", "timeMax": "<RFC3339_END>", "singleEvents": true, "orderBy": "startTime"}' --format json
```

- `singleEvents: true` expands recurring events into individual instances.
- `orderBy: "startTime"` requires `singleEvents: true`.
- For "today": use today at 00:00:00 as timeMin, 23:59:59 as timeMax.
- For "this week": Monday 00:00:00 through Sunday 23:59:59.
- Always include timezone offset (e.g., `2026-03-11T00:00:00-04:00`).

**Use for:** "What's on my calendar today?", "Show my schedule for this week", "What meetings do I have tomorrow?"

### Create Event

```bash
gws calendar events insert --params '{"calendarId": "primary"}' --json '{
  "summary": "<TITLE>",
  "start": {"dateTime": "<RFC3339_START>", "timeZone": "America/New_York"},
  "end": {"dateTime": "<RFC3339_END>", "timeZone": "America/New_York"}
}' --format json
```

- Always confirm event details with the user before creating.
- Parse natural language: "tomorrow at 2pm" → next day 14:00 in user's timezone.
- If no end time given, default to 30 minutes after start.
- To add attendees: include `"attendees": [{"email": "alice@example.com"}]` in the JSON body.
- To add location: include `"location": "Conference Room A"`.
- To add description: include `"description": "Discuss Q2 plans"`.

**Use for:** "Schedule a meeting tomorrow at 2pm", "Add a dentist appointment Friday at 10am", "Block off 1-3pm for focus time"

### Delete Event

```bash
gws calendar events delete --params '{"calendarId": "primary", "eventId": "<EVENT_ID>"}'
```

- First list events to find the matching event ID.
- Confirm with the user before deleting: "I found '**<event summary>**' at <time>. Delete it?"
- If the match is ambiguous, ask which one.

**Use for:** "Cancel my 3pm meeting", "Remove the standup from tomorrow"

### Search Events

```bash
gws calendar events list --params '{"calendarId": "primary", "q": "<SEARCH_TERM>", "timeMin": "<RFC3339_START>", "timeMax": "<RFC3339_END>", "singleEvents": true}' --format json
```

- `q` searches across event summaries and descriptions.
- Always include a reasonable time range (default: next 30 days).

**Use for:** "Do I have any meetings with Alice?", "Find my dentist appointment"

### Find Free Time

No dedicated free-time command. To find availability:

1. List events for the requested date range.
2. Identify gaps between events during working hours (09:00–17:00 in user's timezone).
3. Report available time slots with durations.

**Use for:** "Am I free Thursday afternoon?", "When can I schedule a 1-hour meeting this week?"

### List Calendars

```bash
gws calendar calendarList list --format json
```

**Use for:** "What calendars do I have?", "Show my calendar list"

## Parsing JSON Output

The JSON output contains an `items` array. Each event has:
- `id` — event ID (needed for delete/update)
- `summary` — event title
- `start.dateTime` or `start.date` — start time (dateTime for timed events, date for all-day)
- `end.dateTime` or `end.date` — end time
- `attendees` — list of attendees with `email`, `displayName`, `responseStatus`
- `location` — event location
- `status` — confirmed, tentative, or cancelled
- `htmlLink` — link to open the event in Google Calendar

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
| `gws: command not found` | "The `gws` CLI is not installed. Run `brew install googleworkspace-cli` — see docs/gws-setup.md." |
| `403 insufficient scopes` | "Calendar access not authorized. Run `source .env && gws auth login -s calendar`." |
| `401 authentication` | "Google auth has expired. Run `gws auth login -s calendar` to re-authenticate." |
| No events found | "Your calendar is clear for that time range." |
| Invalid date from user | "I couldn't parse that date. Could you specify it like 'March 10 at 2pm'?" |
| Event not found for deletion | "I couldn't find a matching event. Can you be more specific?" |
