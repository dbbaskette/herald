---
name: obsidian
description: >
  Manages Obsidian vault via the official Obsidian CLI (v1.12+) — search notes,
  read/create/update notes, manage daily notes, list tasks and tags, view backlinks,
  and run commands. Use when asked about notes, knowledge base, vault, daily journal,
  or linked references.
---

# Obsidian Knowledge Base Skill

Manage an Obsidian vault using the official `obsidian` CLI (requires Obsidian 1.12+ with CLI enabled).
The CLI communicates with the running Obsidian desktop app via IPC.

**IMPORTANT: The Obsidian desktop app must be running with CLI enabled (Settings → General → Command line interface → Toggle ON).**

## Prerequisites

The `obsidian` CLI must be registered and the Obsidian app must be running. Test with:

```bash
obsidian version
```

If this fails, tell the user: "The Obsidian CLI is not available. Make sure Obsidian 1.12+ is installed, running, and CLI is enabled in Settings → General → Command line interface → Toggle ON. See https://help.obsidian.md/cli for setup."

### macOS PATH

On macOS, the CLI is at `/Applications/Obsidian.app/Contents/MacOS/obsidian`. The installer adds this to `~/.zprofile`. If it's not on PATH, the user needs:

```bash
export PATH="$PATH:/Applications/Obsidian.app/Contents/MacOS"
```

## CLI Syntax

All commands use `key=value` parameters (not POSIX flags). Quote values with spaces:

```bash
obsidian command param=value param2="value with spaces"
```

- `file=<name>` — resolves like wikilinks (name only, no path/extension needed)
- `path=<path>` — exact path from vault root (e.g. `folder/note.md`)
- If neither is given, most commands default to the active file.

## Commands

### Search Notes

```bash
obsidian search query="meeting notes"
obsidian search query="Spring AI" path="Projects"
obsidian search query="TODO" total
```

- `query=<text>` — (required) search text
- `path=<folder>` — limit to folder
- `limit=<n>` — max results
- `total` — return match count only
- `format=text|json` — output format (default: text)

For search with line context (grep-style `path:line: text` output):

```bash
obsidian search:context query="meeting notes"
```

**Use for:** "Find my notes about Spring AI", "Search for notes mentioning Herald"

### Read a Note

```bash
obsidian read file="Recipe"
obsidian read path="Projects/Herald/architecture.md"
```

- `file=<name>` — file name (wikilink resolution)
- `path=<path>` — exact vault-relative path
- Defaults to active file if neither specified.

**Use for:** "Show me the Herald architecture note", "Read my meeting notes from Friday"

### Create a Note

```bash
obsidian create name="Trip to Paris"
obsidian create name="Meeting Notes" content="# Meeting\n\nAttendees: ..."
obsidian create name="New Project" template="Project Template"
obsidian create name="Quick Note" content="Hello" open
```

- `name=<name>` — file name
- `path=<path>` — exact file path
- `content=<text>` — initial content (use `\n` for newlines)
- `template=<name>` — template to use
- `overwrite` — overwrite if file exists
- `open` — open file after creating

**Use for:** "Create a note called 'Herald Architecture Decisions'", "Make a new meeting note"

### Append to a Note

```bash
obsidian append file="Daily" content="- Met with X about Y"
obsidian append content="## New Section\n\nContent here"
```

- `file=<name>` / `path=<path>` — target file (defaults to active)
- `content=<text>` — (required) content to append
- `inline` — append without leading newline

**Use for:** "Add this to my daily note", "Append these action items to the meeting note"

### Prepend to a Note

```bash
obsidian prepend file="Daily" content="# Priority Tasks"
```

- Same parameters as `append`, inserts after frontmatter.

### Daily Note

```bash
obsidian daily
obsidian daily:read
obsidian daily:append content="- [ ] Buy groceries"
obsidian daily:prepend content="## Top Priority"
obsidian daily:path
```

- `daily` — open today's daily note
- `daily:read` — read daily note contents
- `daily:append` — append content to daily note
- `daily:prepend` — prepend content to daily note
- `daily:path` — get the daily note file path

**Use for:** "What's in my daily note?", "Add a task to today's note"

### Tasks

```bash
obsidian tasks todo
obsidian tasks done
obsidian tasks daily
obsidian tasks file="Project Plan" todo
obsidian tasks todo verbose
```

- `todo` — show incomplete tasks only
- `done` — show completed tasks only
- `daily` — tasks from daily note only
- `file=<name>` / `path=<path>` — filter by file
- `total` — return count only
- `verbose` — group by file with line numbers
- `format=json|tsv|csv` — output format

Toggle or update a task:

```bash
obsidian task ref="Recipe.md:8" toggle
obsidian task daily line=3 done
obsidian task file="Project" line=12 todo
```

**Use for:** "What tasks are open?", "Show incomplete todos from my daily note", "Mark task on line 5 as done"

### Tags

```bash
obsidian tags
obsidian tags counts
obsidian tags sort=count
obsidian tag name="meeting"
```

- `counts` — include tag occurrence counts
- `sort=count` — sort by frequency
- `total` — return tag count only
- `active` — tags for active file only
- `file=<name>` — tags for specific file

**Use for:** "What tags do I use?", "Show notes tagged #meeting"

### Properties (Frontmatter)

```bash
obsidian properties file="Recipe"
obsidian property:read file="Recipe" name="tags"
obsidian property:set file="Recipe" name="status" value="complete"
obsidian property:remove file="Recipe" name="draft"
```

- `property:read` — read a property value
- `property:set` — set/update a property
- `property:remove` — remove a property
- `name=<name>` — (required) property name
- `value=<value>` — (required for set) property value
- `type=text|list|number|checkbox|date|datetime` — property type

**Use for:** "Tag this note with completed", "Set the status to in-progress"

### Backlinks

```bash
obsidian backlinks file="Herald"
obsidian backlinks counts
obsidian backlinks total
```

- `file=<name>` / `path=<path>` — target file
- `counts` — include link counts
- `total` — return backlink count only
- `format=json|tsv|csv` — output format

Related link commands:

```bash
obsidian links file="Herald"
obsidian unresolved
obsidian orphans
obsidian deadends
```

**Use for:** "What notes link to Herald?", "Show orphan notes", "Find broken links"

### Outline

```bash
obsidian outline file="Recipe"
obsidian outline format=md
```

- Shows heading structure of a file.
- `format=tree|md|json` — output format

### Run a Command

```bash
obsidian command id="daily-notes"
obsidian commands filter="daily"
```

- `id=<command-id>` — (required) Obsidian command palette ID to execute
- `commands` — list all available command IDs
- `filter=<prefix>` — filter command list by prefix

**Use for:** "Open the graph view", "Run the Dataview refresh"

### File Management

```bash
obsidian open file="Recipe"
obsidian file file="Recipe"
obsidian files folder="Projects" ext=md
obsidian move file="Old Name" to="Archive/Old Name.md"
obsidian rename file="Draft" name="Final Version"
obsidian delete file="Scratch" permanent
```

### Templates

```bash
obsidian templates
obsidian template:read name="Meeting Notes"
obsidian template:read name="Meeting Notes" resolve
```

- `resolve` — process `{{date}}`, `{{time}}`, `{{title}}` variables

### Vault Info

```bash
obsidian vault
obsidian vaults
```

### Word Count

```bash
obsidian wordcount file="Essay"
obsidian wordcount words
obsidian wordcount characters
```

## Response Formatting

Format responses as clean Telegram-friendly messages (Markdown):

- **Search results:** `• **Note Name** — <snippet>` one per note, most relevant first.
- **Note content:** Bold header, then content. Summarize long notes unless full text requested.
- **Created/appended:** Confirm what was done: "Created **Trip to Paris** with template Travel."
- **Task list:** `• [ ] <task text> (from **<source note>**)` one per task.
- **Backlinks:** `• **<Linking Note>** — <context>` one per linking note.
- **Daily note:** Today's date as header, then content or summary.
- **Empty results:** "No notes found matching that search." / "No incomplete tasks found."

## Error Handling

| Error | Response |
|-------|----------|
| `obsidian: command not found` | "The Obsidian CLI is not on your PATH. Make sure Obsidian 1.12+ is installed and CLI is enabled in Settings → General. See https://help.obsidian.md/cli" |
| Connection refused / timeout | "Can't connect to Obsidian. Make sure the app is running." |
| File not found | "Note '<name>' was not found. Check the name or try searching." |
| File already exists (on create) | "A note named '<name>' already exists. Use append to add content, or pass `overwrite` to replace it." |

## Integration Notes

- **Morning briefing:** Pull `daily:read` content and `tasks daily todo` for the daily summary.
- **Auto-memory sync:** Key facts from Herald memory can be written to an Obsidian note via `create`/`append`.
- **Research:** Search Obsidian with `search query=...` for existing knowledge before using web research.
- **Clipboard:** Add `--copy` to any command to copy output to clipboard instead of stdout.
