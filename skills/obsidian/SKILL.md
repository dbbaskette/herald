---
name: obsidian
description: >
  Manages the Herald-Memory Obsidian vault via the official Obsidian CLI (v1.12+).
  Stores chat sessions, research, daily journals, and structured knowledge.
  Use when asked about notes, knowledge base, vault, daily journal, chat history,
  or linked references.
---

# Obsidian Knowledge Base Skill

**This is NOT a tool you call directly. Use the shell tool to run `obsidian` CLI commands.**

Manage the Obsidian vault using the `obsidian` CLI via shell commands (requires Obsidian 1.12+ with CLI enabled).
The CLI communicates with the running Obsidian desktop app via IPC.

**IMPORTANT: The Obsidian desktop app must be running with CLI enabled (Settings → General → Command line interface → Toggle ON).**

## Critical rules

1. **The command is `obsidian`, NOT `obsidian-cli`.** The CLI is built into the Obsidian desktop app (v1.12+).
2. **ALWAYS include the vault name from CONTEXT.md on every command.** Check the "Obsidian Configuration" section of CONTEXT.md for the vault name and Herald folder path. Without `vault=`, the CLI targets whichever vault is open in the app.
3. **Prefix all paths with the Herald folder from CONTEXT.md.** Herald notes live in a subfolder, not at the vault root.

## Step 0 — ensure the Obsidian CLI is reachable

**Run this before any vault command.** Unlike other skills, Obsidian's CLI ships inside the desktop app — there's no `brew install`. The setup is a detection + guided-fix flow.

### Detect

```bash
command -v obsidian
```

### If missing — walk the user through setup

Stop and guide the user through whichever step they're missing. Don't try to auto-install the app — it's a consumer desktop install with a license check.

**Is the Obsidian desktop app installed?**

```bash
ls /Applications/Obsidian.app 2>/dev/null && echo OK || echo MISSING
```

- **MISSING** → tell the user: "Install Obsidian 1.12+ from [obsidian.md](https://obsidian.md/download) first, then try again."
- **OK** but the `obsidian` command still isn't on `PATH` → the installer sometimes forgets to update the shell rc. Tell the user:

  ```bash
  echo 'export PATH="$PATH:/Applications/Obsidian.app/Contents/MacOS"' >> ~/.zprofile
  source ~/.zprofile
  command -v obsidian
  ```

  (Use `~/.zprofile` for zsh, `~/.bash_profile` for bash. Confirm the user's shell with `echo $SHELL` if unsure.)

**Is the CLI toggle on?**

The user has to flip this manually — Herald can't poke Obsidian's settings. Tell the user:

> Open Obsidian → Settings → General → Command line interface → toggle ON. Then send your message again.

**Is the app running?**

The CLI talks to the running Obsidian process via IPC. If the app is closed, CLI commands hang or fail. Probe with a cheap command:

```bash
obsidian vault 2>&1 | head -3
```

A response within 3 seconds = app is up. Timeout or "connection refused" = app isn't running. Tell the user to open Obsidian.

### Smoke-test once everything's aligned

```bash
obsidian vault vault="<vault-name-from-CONTEXT.md>"
```

Should list the open vault. If it errors on an unknown vault name, check CONTEXT.md — the Obsidian Configuration section should have the right vault name.

## Herald-Memory Vault

Herald uses a dedicated vault called **Herald-Memory** with a structured folder layout. All Herald-generated content goes here — never into the user's personal vaults unless explicitly asked.

**IMPORTANT:** Always include the vault name and Herald folder prefix from CONTEXT.md on every CLI command.

### Folder Structure

```
Herald-Memory/
├── Chat-Sessions/          # Archived conversations
│   ├── 2026-03-10-weather-raleigh.md
│   ├── 2026-03-10-obsidian-setup.md
│   └── ...
├── Daily/                  # Daily journals and briefings
│   ├── 2026-03-10.md
│   └── ...
├── Research/               # Web research, deep dives, reports
│   ├── spring-ai-chat-memory.md
│   └── ...
├── Projects/               # Project-specific notes and decisions
│   ├── Herald/
│   │   ├── architecture.md
│   │   └── decisions.md
│   └── ...
├── People/                 # People and contacts referenced in conversations
│   └── ...
├── Reference/              # How-tos, cheat sheets, config snippets
│   └── ...
└── Templates/              # Note templates
    ├── Chat Session.md
    ├── Daily Briefing.md
    └── Research.md
```

### Folder Conventions

| Folder | Purpose | When to write |
|--------|---------|---------------|
| `Chat-Sessions/` | Conversation archives | After meaningful conversations (not trivial Q&A) |
| `Daily/` | Daily journals, briefing summaries | Morning briefings, end-of-day recaps |
| `Research/` | Deep research output | After web research, multi-source analysis |
| `Projects/` | Project notes, architecture decisions | When discussing project-specific topics |
| `People/` | Contact/person notes | When learning about people the user works with |
| `Reference/` | Reusable reference material | Setup guides, CLI cheat sheets, config notes |

### Bootstrap

If the vault exists but the folder structure hasn't been created yet, create the folders on first use:

```bash
obsidian create vault="Documents" path="Herald-Memory/Chat-Sessions/.gitkeep" content="" overwrite
obsidian create vault="Documents" path="Herald-Memory/Daily/.gitkeep" content="" overwrite
obsidian create vault="Documents" path="Herald-Memory/Research/.gitkeep" content="" overwrite
obsidian create vault="Documents" path="Herald-Memory/Projects/.gitkeep" content="" overwrite
obsidian create vault="Documents" path="Herald-Memory/People/.gitkeep" content="" overwrite
obsidian create vault="Documents" path="Herald-Memory/Reference/.gitkeep" content="" overwrite
obsidian create vault="Documents" path="Herald-Memory/Templates/Chat Session.md" content="---\ntags: [chat-session]\ndate: {{date}}\ntopic: \nconversation-id: \n---\n\n# {{title}}\n\n## Summary\n\n## Key Points\n\n## Action Items\n\n- [ ] \n\n## Related\n\n- \n\n## Full Conversation\n\n" overwrite
obsidian create vault="Documents" path="Herald-Memory/Templates/Daily Briefing.md" content="---\ntags: [daily]\ndate: {{date}}\n---\n\n# Daily Briefing — {{date}}\n\n## Weather\n\n## Calendar\n\n## Top Priorities\n\n## Notes\n\n## Related\n\n- \n" overwrite
obsidian create vault="Documents" path="Herald-Memory/Templates/Research.md" content="---\ntags: [research]\ndate: {{date}}\ntopic: \n---\n\n# {{title}}\n\n## Summary\n\n## Sources\n\n## Key Findings\n\n## Next Steps\n\n## Related\n\n- \n" overwrite
```

Also create a top-level vault index and a Herald project index on first use:

```bash
obsidian create vault="Documents" path="Herald-Memory/Index.md" content="---\ntags: [index]\n---\n\n# Herald-Memory Index\n\nEntry point for the Herald-Memory vault. Link new top-level notes here.\n\n## Projects\n\n- [[Projects/Herald/Index]]\n\n## Sections\n\n- Chat-Sessions/\n- Daily/\n- Research/\n- Projects/\n- People/\n- Reference/\n"
obsidian create vault="Documents" path="Herald-Memory/Projects/Herald/Index.md" content="---\ntags: [index, project/herald]\n---\n\n# Herald — Project Index\n\n## Architecture & Decisions\n\n- \n\n## Open Questions\n\n- \n\n## Related Research\n\n- \n\n## Recent Chat Sessions\n\n- \n"
```

## Linking Conventions

Notes are only as useful as the connections between them. Every note you create should link out to related context using Obsidian `[[wikilinks]]`, so the graph view and backlinks stay populated.

### Wikilinks in generated content

Use wikilinks — not plain text — when a note references another note. Link targets use the path from the vault root without the `.md` extension, and can be given a display alias with `|`:

```markdown
Relates to [[Projects/Herald/architecture|the architecture doc]] and the
earlier session [[Chat-Sessions/2026-03-10-herald-model-config]].
Follows up on [[Research/spring-ai-jdbc-chat-memory]].
```

Link out whenever a note mentions:

- A project discussed elsewhere → `[[Projects/<name>/Index]]` or a specific project note.
- A person → `[[People/<name>]]`.
- Prior research on the topic → `[[Research/<prior-note>]]`.
- An earlier chat session that set context → `[[Chat-Sessions/YYYY-MM-DD-<topic>]]`.
- A reference/cheat sheet that's relevant → `[[Reference/<note>]]`.

Every generated note should include a `## Related` section at the bottom listing the wikilinks it depends on. If there are no related notes, keep the heading and leave a single `- ` bullet — future sessions will fill it in.

### Index notes

Index notes are aggregator pages that list wikilinks to everything in a scope. They turn a folder full of notes into a navigable hub.

- `Herald-Memory/Index.md` — top-level vault entry point. Link to each project index and the major sections.
- `Projects/<name>/Index.md` — per-project hub. Sections for Architecture & Decisions, Open Questions, Related Research, Recent Chat Sessions.
- Optional per-section indexes (e.g. `Research/Index.md`) if a section grows large.

When creating a note that belongs in a scope that has an index, **append a wikilink to that index** so it stays current. Use `obsidian append` rather than regenerating the index:

```bash
obsidian append vault="Documents" file="Projects/Herald/Index" content="- [[Chat-Sessions/2026-03-10-herald-model-config]]"
```

If an expected index doesn't exist yet, create it using the structure shown in the Bootstrap block above, then add the link.

### Discrete concept notes

Prefer many small single-concept notes over one large dump. When a chat session covers multiple distinct topics (e.g. a model-routing question and an unrelated Obsidian setup issue), split them:

1. Write each distinct reusable concept as its own note under `Reference/` or the relevant `Projects/<name>/` folder.
2. In the Chat-Session summary, link to those notes from the `## Related` section rather than duplicating their content.

Rule of thumb: if a section of a note would make sense on its own and might be referenced later, it's probably its own note.

## Saving Chat Sessions

After a meaningful conversation (not trivial greetings or single-question lookups), archive it to the vault. This is how Herald builds long-term memory beyond the key-value store.

### When to save

- Conversations that involved research, decisions, or multi-step problem solving
- Conversations where the user shared preferences, context, or project details worth remembering
- Conversations that produced action items or follow-ups
- Morning briefings and weekly reviews

### How to save

1. Generate a descriptive filename: `YYYY-MM-DD-<short-topic>.md`
2. Write a structured summary (not a raw transcript):

```bash
obsidian create vault="Documents" path="Herald-Memory/Chat-Sessions/2026-03-10-herald-model-config.md" content="---\ntags: [chat-session, project/herald]\ndate: 2026-03-10\ntopic: Herald model configuration\nconversation-id: web-console\n---\n\n# Herald Model Configuration\n\n## Summary\nInvestigated why web chat reported Claude 3.7 Sonnet while Telegram showed claude-sonnet-4-5. Root cause was stale conversation history in SPRING_AI_CHAT_MEMORY. See [[Projects/Herald/architecture]] for the advisor chain context.\n\n## Key Points\n- Both Telegram and web chat use the same AgentService.chat() code path\n- Stale messages in SPRING_AI_CHAT_MEMORY replayed wrong model identity\n- Added {model_id} to system prompt for consistent self-identification\n- Created ToolPairSanitizingAdvisor to fix orphaned tool message pairs (see [[Reference/tool-pair-sanitizing]])\n\n## Action Items\n- [x] Clear stale web-console conversation\n- [x] Add model_id to system prompt\n- [x] Create ToolPairSanitizingAdvisor\n\n## Related\n- [[Projects/Herald/Index]]\n- [[Projects/Herald/architecture]]\n- [[Research/spring-ai-chat-memory]]\n" overwrite
```

Then keep the project index current:

```bash
obsidian append vault="Documents" file="Projects/Herald/Index" content="- [[Chat-Sessions/2026-03-10-herald-model-config]]"
```

### What to include in the summary

- **Topic** — one-line description
- **Summary** — 2-3 sentences of what happened
- **Key Points** — bullet points of important facts, decisions, or discoveries
- **Action Items** — tasks that came out of the conversation (with completion status)
- **Tags** — `chat-session` plus any relevant topic tags

### What NOT to include

- Raw message transcripts (too verbose, low signal)
- Trivial exchanges ("what time is it", "hello")
- Sensitive credentials or API keys

## Saving Research

When Herald performs web research or deep analysis, save the findings:

```bash
obsidian create vault="Documents" path="Herald-Memory/Research/spring-ai-jdbc-chat-memory.md" content="---\ntags: [research, spring-ai]\ndate: 2026-03-10\ntopic: Spring AI JDBC Chat Memory\n---\n\n# Spring AI JDBC Chat Memory\n\n## Summary\n...\n\n## Sources\n- https://...\n\n## Key Findings\n- ...\n\n## Related\n- [[Projects/Herald/Index]]\n- [[Research/spring-ai-chat-memory]]\n" overwrite
```

## Saving Daily Briefings

Morning briefings and weekly reviews go to `Daily/`:

```bash
obsidian create vault="Documents" path="Herald-Memory/Daily/2026-03-10.md" content="---\ntags: [daily]\ndate: 2026-03-10\n---\n\n# Monday, March 10, 2026\n\n## Weather\nRaleigh: 62°F, partly cloudy\n\n## Calendar\n- 10:00 AM — Team standup\n\n## Top Priorities\n1. Fix Herald chat scroll — see [[Projects/Herald/Index]]\n2. Deploy UI updates\n\n## Notes\n- Discussed model routing in [[Chat-Sessions/2026-03-10-herald-model-config]]\n\n## Related\n- [[Projects/Herald/Index]]\n" overwrite
```

## Referencing Past Context

Before answering questions, search the vault for relevant prior context:

```bash
# Search chat sessions for prior discussions
obsidian search vault="Documents" query="Spring AI" path="Herald-Memory/Chat-Sessions"

# Search research notes
obsidian search vault="Documents" query="model switching" path="Herald-Memory/Research"

# Search project notes
obsidian search vault="Documents" query="architecture" path="Herald-Memory/Projects/Herald"

# Read a specific note for full context
obsidian read vault="Documents" path="Herald-Memory/Chat-Sessions/2026-03-10-herald-model-config.md"
```

Use this to:
- Recall previous conversations about a topic before answering
- Find prior research before starting new research
- Check if a question was already answered in a past session
- Surface related context the user might not remember

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

### Read a Note

```bash
obsidian read file="Recipe"
obsidian read path="Projects/Herald/architecture.md"
```

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

### Append / Prepend

```bash
obsidian append file="Daily" content="- Met with X about Y"
obsidian prepend file="Daily" content="# Priority Tasks"
```

### Daily Note

```bash
obsidian daily
obsidian daily:read
obsidian daily:append content="- [ ] Buy groceries"
obsidian daily:prepend content="## Top Priority"
obsidian daily:path
```

### Tasks

```bash
obsidian tasks todo
obsidian tasks done
obsidian tasks daily
obsidian tasks file="Project Plan" todo
obsidian tasks todo verbose
```

Toggle or update a task:

```bash
obsidian task ref="Recipe.md:8" toggle
obsidian task daily line=3 done
obsidian task file="Project" line=12 todo
```

### Tags

```bash
obsidian tags
obsidian tags counts
obsidian tags sort=count
obsidian tag name="meeting"
```

### Properties (Frontmatter)

```bash
obsidian properties file="Recipe"
obsidian property:read file="Recipe" name="tags"
obsidian property:set file="Recipe" name="status" value="complete"
obsidian property:remove file="Recipe" name="draft"
```

### Backlinks

```bash
obsidian backlinks file="Herald"
obsidian links file="Herald"
obsidian unresolved
obsidian orphans
obsidian deadends
```

### Outline

```bash
obsidian outline file="Recipe"
obsidian outline format=md
```

### Run a Command

```bash
obsidian command id="daily-notes"
obsidian commands filter="daily"
```

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

### Vault Info

```bash
obsidian vault
obsidian vaults
```

### Word Count

```bash
obsidian wordcount file="Essay"
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
| Vault not found | "The Herald-Memory vault was not found. Create it in Obsidian: File → Create new vault → name it 'Herald-Memory'. See docs/obsidian-setup.md." |

## Integration Notes

- **Morning briefing:** Pull `daily:read` and `tasks daily todo`, then save the briefing to `Daily/`.
- **Chat session archival:** After meaningful conversations, summarize and save to `Chat-Sessions/`.
- **Research persistence:** Save web research findings to `Research/` for future reference.
- **Memory sync:** Key facts from Herald's key-value memory can be cross-referenced with vault notes.
- **Prior context lookup:** Search `Chat-Sessions/` and `Research/` before answering questions to recall prior discussions.
- **Index upkeep:** When creating a note in a scope that has an `Index.md`, append a wikilink to the index so it stays current (see Linking Conventions).
- **Clipboard:** Add `--copy` to any command to copy output to clipboard instead of stdout.
