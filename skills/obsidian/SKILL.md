---
name: obsidian
description: >
  Manages the Herald-Memory Obsidian vault via the official Obsidian CLI (v1.12+).
  Stores chat sessions, research, daily journals, and structured knowledge.
  Use when asked about notes, knowledge base, vault, daily journal, chat history,
  or linked references.
---

# Obsidian Knowledge Base Skill

Manage the **Herald-Memory** Obsidian vault using the official `obsidian` CLI (requires Obsidian 1.12+ with CLI enabled).
The CLI communicates with the running Obsidian desktop app via IPC.

**IMPORTANT: The Obsidian desktop app must be running with CLI enabled (Settings → General → Command line interface → Toggle ON).**

## Prerequisites

**Two critical rules:**
1. **The command is `obsidian`, NOT `obsidian-cli`.** The CLI is built into the Obsidian desktop app (v1.12+).
2. **ALWAYS include `vault="Herald-Memory"` on every command.** Without it, the CLI targets whichever vault is open in the app, which may be the wrong one.

The Obsidian app must be running with CLI enabled. Test with:

```bash
obsidian vault vault="Herald-Memory"
```

If this fails, tell the user: "The Obsidian CLI is not available. Make sure Obsidian 1.12+ is installed, running, and CLI is enabled in Settings → General → Command line interface → Toggle ON. See https://help.obsidian.md/cli for setup."

### macOS PATH

On macOS, the CLI is at `/Applications/Obsidian.app/Contents/MacOS/obsidian`. The installer adds this to `~/.zprofile`. If it's not on PATH, the user needs:

```bash
export PATH="$PATH:/Applications/Obsidian.app/Contents/MacOS"
```

## Herald-Memory Vault

Herald uses a dedicated vault called **Herald-Memory** with a structured folder layout. All Herald-generated content goes here — never into the user's personal vaults unless explicitly asked.

**IMPORTANT:** Always include `vault="Herald-Memory"` on every CLI command to avoid targeting the wrong vault (the CLI defaults to whichever vault is currently open in the Obsidian app).

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
obsidian create vault="Herald-Memory" path="Chat-Sessions/.gitkeep" content="" overwrite
obsidian create vault="Herald-Memory" path="Daily/.gitkeep" content="" overwrite
obsidian create vault="Herald-Memory" path="Research/.gitkeep" content="" overwrite
obsidian create vault="Herald-Memory" path="Projects/.gitkeep" content="" overwrite
obsidian create vault="Herald-Memory" path="People/.gitkeep" content="" overwrite
obsidian create vault="Herald-Memory" path="Reference/.gitkeep" content="" overwrite
obsidian create vault="Herald-Memory" path="Templates/Chat Session.md" content="---\ntags: [chat-session]\ndate: {{date}}\ntopic: \nconversation-id: \n---\n\n# {{title}}\n\n## Summary\n\n## Key Points\n\n## Action Items\n\n- [ ] \n\n## Full Conversation\n\n" overwrite
obsidian create vault="Herald-Memory" path="Templates/Daily Briefing.md" content="---\ntags: [daily]\ndate: {{date}}\n---\n\n# Daily Briefing — {{date}}\n\n## Weather\n\n## Calendar\n\n## Top Priorities\n\n## Notes\n\n" overwrite
obsidian create vault="Herald-Memory" path="Templates/Research.md" content="---\ntags: [research]\ndate: {{date}}\ntopic: \n---\n\n# {{title}}\n\n## Summary\n\n## Sources\n\n## Key Findings\n\n## Next Steps\n\n" overwrite
```

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
obsidian create vault="Herald-Memory" path="Chat-Sessions/2026-03-10-herald-model-config.md" content="---\ntags: [chat-session]\ndate: 2026-03-10\ntopic: Herald model configuration\nconversation-id: web-console\n---\n\n# Herald Model Configuration\n\n## Summary\nInvestigated why web chat reported Claude 3.7 Sonnet while Telegram showed claude-sonnet-4-5. Root cause was stale conversation history in SPRING_AI_CHAT_MEMORY.\n\n## Key Points\n- Both Telegram and web chat use the same AgentService.chat() code path\n- Stale messages in SPRING_AI_CHAT_MEMORY replayed wrong model identity\n- Added {model_id} to system prompt for consistent self-identification\n- Created ToolPairSanitizingAdvisor to fix orphaned tool message pairs\n\n## Action Items\n- [x] Clear stale web-console conversation\n- [x] Add model_id to system prompt\n- [x] Create ToolPairSanitizingAdvisor\n" overwrite
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
obsidian create vault="Herald-Memory" path="Research/spring-ai-jdbc-chat-memory.md" content="---\ntags: [research, spring-ai]\ndate: 2026-03-10\ntopic: Spring AI JDBC Chat Memory\n---\n\n# Spring AI JDBC Chat Memory\n\n## Summary\n...\n\n## Sources\n- https://...\n\n## Key Findings\n- ...\n" overwrite
```

## Saving Daily Briefings

Morning briefings and weekly reviews go to `Daily/`:

```bash
obsidian create vault="Herald-Memory" path="Daily/2026-03-10.md" content="---\ntags: [daily]\ndate: 2026-03-10\n---\n\n# Monday, March 10, 2026\n\n## Weather\nRaleigh: 62°F, partly cloudy\n\n## Calendar\n- 10:00 AM — Team standup\n\n## Top Priorities\n1. Fix Herald chat scroll\n2. Deploy UI updates\n\n## Notes\n- ...\n" overwrite
```

## Referencing Past Context

Before answering questions, search the vault for relevant prior context:

```bash
# Search chat sessions for prior discussions
obsidian search vault="Herald-Memory" query="Spring AI" path="Chat-Sessions"

# Search research notes
obsidian search vault="Herald-Memory" query="model switching" path="Research"

# Search project notes
obsidian search vault="Herald-Memory" query="architecture" path="Projects/Herald"

# Read a specific note for full context
obsidian read vault="Herald-Memory" path="Chat-Sessions/2026-03-10-herald-model-config.md"
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
- **Clipboard:** Add `--copy` to any command to copy output to clipboard instead of stdout.
