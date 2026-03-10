---
name: google-calendar
description: Manages Google Calendar via gws CLI — list, create, delete, and search
  events, find free time slots. Use when asked about calendar, schedule, meetings,
  or availability.
---

## Instructions

Use the `gws` CLI to interact with Google Calendar. Always use `--format json` for parseable output and `--calendar-id primary` unless the user specifies a different calendar.

### Listing Events

To show events for a time range:

```bash
gws calendar events list --calendar-id primary --time-min "<ISO8601>" --time-max "<ISO8601>" --format json
```

- For "today": use today's date at 00:00:00 as `--time-min` and 23:59:59 as `--time-max` in the user's timezone
- For "this week": use Monday 00:00:00 through Sunday 23:59:59
- Always include timezone offset (e.g., `2026-03-09T00:00:00-05:00`)

### Creating Events

```bash
gws calendar events create --calendar-id primary --summary "<title>" --start "<ISO8601>" --end "<ISO8601>" --format json
```

- Parse natural language times: "tomorrow at 2pm" -> next day at 14:00 in user's timezone
- Default event duration is 30 minutes if no end time is specified
- If attendees are mentioned but no email is available, note this in the response and create the event without attendees

### Deleting Events

```bash
gws calendar events delete --calendar-id primary --event-id <id>
```

- First list events to find the matching event ID before deleting
- Confirm with the user before deleting if the match is ambiguous (multiple events with similar names)

### Searching Events

```bash
gws calendar events list --calendar-id primary --q "<search term>" --format json
```

- Use `--q` for keyword search across event summaries and descriptions

### Finding Free Time

1. List events for the requested date range
2. Identify gaps between events during working hours (09:00-17:00 by default)
3. Report available time slots

### Parsing JSON Output

The JSON output contains an `items` array. Each event has:
- `id` — event ID (needed for delete/update)
- `summary` — event title
- `start.dateTime` or `start.date` — start time
- `end.dateTime` or `end.date` — end time
- `attendees` — list of attendees
- `location` — event location
- `status` — confirmed, tentative, or cancelled

Format responses as clean, readable messages:
- Use bullet points or numbered lists for multiple events
- Include time, title, and location (if set)
- For free time, list available slots with duration

### Error Handling

- **`gws: command not found`**: Tell the user to install gws: `brew install gws` or see https://github.com/nicholasgasior/gws
- **Authentication errors** (expired token, 401): Tell the user to re-authenticate with `gws auth login`
- **No events found**: Respond naturally (e.g., "Your calendar is clear for today")
- **Invalid date input**: Ask the user to clarify the date or time

### Example Prompts

- "What's on my calendar today?" → List events for today
- "Schedule a meeting with X tomorrow at 2pm" → Create a 30-minute event
- "Am I free Thursday afternoon?" → List Thursday 12:00-17:00 events, report gaps
- "Cancel my 3pm meeting" → Search for events at 3pm today, confirm, then delete
- "What do I have this week?" → List events Monday through Friday
