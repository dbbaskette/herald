---
name: reminders
description: >
  Read, create, complete, and delete Apple Reminders via the `reminders` CLI.
  Use when the user asks about Reminders.app content — "what's on my list",
  "remind me to X", "add Y to Groceries", "clear completed reminders", or any
  phrasing that implies the native macOS Reminders app.
---

# Apple Reminders Skill

**macOS only.** Uses the [`reminders`](https://github.com/keith/reminders-cli) CLI (via `brew install keith/formulae/reminders-cli`) which talks to Reminders.app through Apple's EventKit — same data your iPhone and Watch see.

Herald's `RemindersTools` wraps the CLI with five `@Tool` methods. This skill tells you when to use each one and the common patterns.

## Step 0 — ensure reminders-cli is installed and authorized

**Run this before any other recipe in this skill.** Idempotent.

### Detect

```bash
command -v reminders
```

### If missing — install

Confirm via `askUserQuestion`:

> To read/write Apple Reminders, I need the `reminders` CLI by Keith Smiley (~2 MB).
> Run `brew install keith/formulae/reminders-cli`?

Then:

```bash
brew install keith/formulae/reminders-cli
reminders --version
```

macOS only — if `os.name` doesn't start with "Mac", stop here and tell the user reminders-cli isn't available on their platform.

### Check Reminders access (Privacy grant)

The CLI needs Reminders access granted in System Settings → Privacy & Security → Reminders. Probe it:

```bash
reminders show-lists
```

- **Succeeds** → access is granted, proceed to Step 1.
- **Empty output + no error** → possibly no lists exist yet; prompt the user to confirm.
- **Error mentioning "permissions" / "denied" / "authorization"** → the Privacy grant is missing. Tell the user:

  > macOS is blocking access to Reminders. Open **System Settings → Privacy & Security → Reminders** and toggle on the entry for Terminal (or whichever app is running Herald). Then send your message again.

  Don't keep retrying automatically — the user has to flip the toggle manually.

### If `RemindersTools` returns an "unavailable" error

If a Herald `reminders_*` tool call returns `{"error": "Apple Reminders CLI (reminders) is not available..."}`, it means the RemindersAvailabilityChecker at startup didn't find the CLI. The user installed it after Herald booted — tell them Herald needs a restart to pick up the new CLI, or re-run the check manually with `command -v reminders`.

## Tools

| Tool | Purpose |
|---|---|
| `reminders_list_names()` | List the user's Reminders lists. Always call this first when you don't know which list the user means. |
| `reminders_show(listName)` | Show incomplete reminders in a list. Pass `null` or `""` to span all lists. |
| `reminders_create(listName, title, dueDate, notes)` | Add a new reminder. `dueDate` and `notes` are optional. |
| `reminders_complete(listName, index)` | Mark a reminder done by its index in the list. |
| `reminders_delete(listName, index)` | Delete a reminder by index. Prefer `complete` unless the reminder was a mistake. |

All return JSON. Errors come as `{"error": "..."}` — check for that key before parsing.

## Common patterns

### "What's on my Groceries list?"

```
1. reminders_show("Groceries")
2. Parse the JSON, render the titles
```

### "Remind me to call Jamie tomorrow at 2pm"

```
1. reminders_list_names()  — confirm the default list exists
2. reminders_create("Reminders", "Call Jamie", "tomorrow 2pm", null)
```

If the user doesn't specify a list, prefer their default "Reminders" list. If that list doesn't exist, ask which list to use.

### "What reminders do I have due today?"

```
1. reminders_show(null)    — span all lists
2. Filter the JSON for items with a due-date on today's date
3. Group by list, show titles + times
```

### "Mark 'Buy milk' done"

```
1. reminders_show("Groceries")  — find the matching index
2. reminders_complete("Groceries", <index>)
```

Always verify the match before completing. If two reminders look similar, ask which one.

### "Add these to Groceries: milk, eggs, bread"

```
Dispatch in one turn:
  reminders_create("Groceries", "Milk", null, null)
  reminders_create("Groceries", "Eggs", null, null)
  reminders_create("Groceries", "Bread", null, null)
```

## Due-date conventions

The CLI accepts natural-language dates:

- `"tomorrow 3pm"`, `"Thursday 9am"`, `"next Monday"`
- ISO-8601 like `"2026-05-01T14:00"`
- `"today 6pm"` — make sure it's in the future; past times are accepted but pointless

When the user says "in a few minutes" / "shortly" / similar vague phrases, ask for a specific time rather than guessing.

## Guardrails

- **Never delete silently.** Unless the user says "delete" explicitly, use `reminders_complete` instead — completions can be restored in Reminders.app, deletions cannot.
- **Don't batch destructive ops.** If the user asks to clear multiple reminders, list them first and confirm the names before deleting.
- **Respect the list name.** Don't invent list names — always call `reminders_list_names()` if unsure.
- **Non-macOS environments:** the tools return `{"error": "Apple Reminders CLI ..."}`. Surface the error as-is; don't retry.

## Not in scope

- **Recurring reminders** — the CLI has limited support; if the user wants "every Monday 9am" tell them to create the recurring pattern in Reminders.app directly.
- **Shared lists** — reads and writes work but shared-list status isn't surfaced in the output.
- **Subtasks** — not exposed by the CLI.

## Related

- `skills/google-calendar/SKILL.md` — events, not action items; use that for meetings.
- Long-term memory — for durable facts or decisions, use `MemoryCreate`; Reminders is for transient action items.
