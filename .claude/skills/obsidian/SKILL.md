---
name: obsidian
description: >
  Manages Obsidian vault via Obsidian CLI — search notes, read/create/update notes
  with YAML properties and backlinks, manage daily notes, list tasks, and run commands.
  Use when asked about notes, knowledge base, vault, daily journal, or linked references.
---

# Obsidian Knowledge Base Skill

Manage an Obsidian vault using the `obsidian` CLI tool (v1.12+). The CLI communicates with the running Obsidian desktop app via IPC, providing full access to vault features including backlinks, YAML properties, Dataview refreshes, and plugin actions.

**IMPORTANT: The Obsidian desktop app must be running with CLI enabled (Settings → Command line interface → Toggle ON).**

## Prerequisites

The `obsidian` CLI must be installed and the Obsidian app must be running. Test with:

```bash
obsidian search "test" 2>&1 | head -5
```

If this fails, tell the user: "The Obsidian CLI is not available. Make sure Obsidian is running and CLI is enabled in Settings → Command line interface → Toggle ON."

## Commands

### Search Notes

```bash
obsidian search "query"
```

- Full-text search across the entire vault.
- Returns matching notes with context snippets.
- Use specific keywords for more precise results.

**Use for:** "Find my notes about Spring AI", "Search for notes mentioning Herald", "What do I have about microservices?"

### Search by Tag

```bash
obsidian search --tag "tag-name"
```

- Find all notes with a specific tag.
- Supports nested tags: `--tag "project/herald"`.
- Combine with text search for narrower results.

**Use for:** "Show me notes tagged #meeting from this week", "Find all project/herald notes", "What notes have the #architecture tag?"

### Read Note

```bash
obsidian cat "Note Name"
```

Or by path:

```bash
obsidian cat "path/to/note.md"
```

- Returns the full content of a note including YAML frontmatter.
- Use the note name (without `.md`) or the full vault-relative path.
- Parse YAML frontmatter to understand note metadata (tags, aliases, dates, etc.).

**Use for:** "Show me the Herald architecture note", "Read my meeting notes from Friday", "What's in the project roadmap note?"

### Create Note

```bash
obsidian create "Note Name" --content "Markdown content here" --properties '{"tags": ["tag1", "tag2"], "date": "2026-03-09"}'
```

- Creates a new note with optional content and YAML properties.
- The `--properties` flag accepts a JSON object that becomes YAML frontmatter.
- Use `[[Note Name]]` syntax in content to create backlinks to other notes.
- Common properties: `tags`, `date`, `aliases`, `cssclasses`, custom fields.
- If the note already exists, the command will fail — use `append` or `properties set` to modify existing notes.

**Use for:** "Create a note called 'Herald Architecture Decisions'", "Make a new meeting note for today's standup", "Start a new project note with these tags"

### Append to Note

```bash
obsidian append "Note Name" --content "Additional content here"
```

- Appends content to the end of an existing note.
- Use for adding entries to running notes, daily logs, or lists.
- Content is appended as-is — include newlines and formatting as needed.
- Use `[[backlinks]]` in content to link to other notes.

**Use for:** "Add this to my daily note", "Append these action items to the meeting note", "Log this to my Herald development journal"

### Update Properties

```bash
obsidian properties set "Note Name" --key value
```

- Updates or adds YAML frontmatter properties on an existing note.
- Use for tagging, setting status, adding dates, or custom metadata.
- Property values can be strings, numbers, booleans, or arrays.

**Use for:** "Tag this note with #completed", "Set the status to 'in-progress'", "Add the 'herald' tag to that note"

### Daily Note

```bash
obsidian daily
```

- Opens or creates today's daily note using the configured daily note template.
- Returns the content of the daily note.
- Use `obsidian append` with the daily note name to add entries.

**Use for:** "What's in my daily note?", "Open today's daily note", "Add this to today's journal"

### List Tasks

```bash
obsidian tasks --status incomplete
```

- Lists incomplete tasks (`- [ ]`) from across the vault.
- Returns tasks with their source note for context.
- For completed tasks: `--status complete`.
- For all tasks: omit the `--status` flag.

**Use for:** "What tasks are open in my project notes?", "Show my incomplete to-dos", "List all open tasks"

### Backlinks

```bash
obsidian backlinks "Note Name"
```

- Finds all notes that link to the specified note via `[[Note Name]]`.
- Returns linking note names and the context around each link.
- Useful for understanding how a concept connects to other notes.

**Use for:** "What notes link to the 'Herald' note?", "Show backlinks to my architecture decisions", "What references this note?"

### Run Command

```bash
obsidian command "command-id"
```

- Triggers any Obsidian command palette action by its command ID.
- Use for plugin actions, workspace commands, or built-in operations.
- Common commands: `daily-notes`, `graph:open`, `editor:toggle-source`.

**Use for:** "Open the graph view", "Run the Dataview refresh", "Toggle reading mode"

## Response Formatting

Format responses as clean Telegram-friendly messages (Markdown):

- **Search results:** `• **Note Name** — <snippet or summary>` one per note, most relevant first. Include the path if notes share names.
- **Note content:** Show the note title as a bold header, then the content. Summarize long notes unless the user asks for the full text.
- **Created note:** "Note created: **<Note Name>** with tags: `tag1`, `tag2`. Added backlinks to: [[linked note]]."
- **Appended content:** "Added to **<Note Name>**: <brief summary of what was appended>."
- **Task list:** `• ☐ <task text> (from **<source note>**)` one per task.
- **Backlinks:** `• **<Linking Note>** — <context snippet around the link>` one per linking note.
- **Daily note:** Show today's date as header, then the note content or a summary.
- **Empty results:** "No notes found matching that search." or "No incomplete tasks found in your vault."

## Error Handling

| Error | Response |
|-------|----------|
| `obsidian: command not found` | "The Obsidian CLI is not installed. Install Obsidian v1.12+ and enable the CLI in Settings → Command line interface." |
| Connection refused / IPC error | "Can't connect to Obsidian. Make sure the app is running and CLI is enabled in Settings → Command line interface → Toggle ON." |
| Note not found | "Note '<name>' was not found in your vault. Check the name or try searching for it." |
| Note already exists (on create) | "A note named '<name>' already exists. Use append to add content or read it first." |
| Vault locked / sync conflict | "Your vault appears to be locked or syncing. Wait a moment and try again." |
| Invalid properties JSON | "The properties format was invalid. Properties should be valid JSON, e.g., `{\"tags\": [\"example\"]}`." |
| No results | "No notes found matching that search." |

## Example Interactions

**"Find my notes about Spring AI"**
→ Run `obsidian search "Spring AI"`. List matching notes with snippets. Offer to read any specific note.

**"Add this to my daily note: met with X about Y"**
→ Run `obsidian daily` to get today's daily note name. Then `obsidian append "<daily note>" --content "\n- Met with X about Y"`. Confirm the addition.

**"Create a note called 'Herald Architecture Decisions' with these points..."**
→ Run `obsidian create "Herald Architecture Decisions" --content "<formatted points>" --properties '{"tags": ["herald", "architecture"]}'`. Include `[[Herald]]` backlink in the content.

**"What tasks are open in my project notes?"**
→ Run `obsidian tasks --status incomplete`. Group tasks by source note. Highlight any with due dates.

**"What notes link to the 'Herald' note?"**
→ Run `obsidian backlinks "Herald"`. List each linking note with the context around the link.

**"Show me notes tagged #meeting from this week"**
→ Run `obsidian search --tag "meeting"`. Filter results to recent notes. Show note names with dates and snippets.

## Integration Notes

- **Morning briefing:** Pull daily note content and incomplete tasks for the daily summary.
- **Auto-memory sync:** Key facts from Herald memory can be synced to an Obsidian "Herald Memory" note.
- **Research:** Search Obsidian for existing knowledge before performing web research with the research subagent.
