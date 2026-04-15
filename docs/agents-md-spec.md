# agents.md Format Specification

## Overview

`agents.md` is a single-file agent configuration format for Herald's ephemeral mode. It combines YAML frontmatter (metadata and configuration) with a markdown body (the agent's system prompt) in one portable file. This format allows you to define, share, and run an agent by pointing Herald at a single `.md` file.

Herald's ephemeral mode (`herald run <file>`) reads the frontmatter to configure the agent's model, provider, and tools, then uses the markdown body as the system prompt.

## Format

The file consists of YAML frontmatter delimited by `---` lines, followed by a markdown body that serves as the system prompt.

```
---
<yaml frontmatter>
---

<markdown system prompt>
```

The frontmatter is optional. If omitted, all fields use their defaults. The markdown body is also optional; if absent, the agent has no system prompt.

## Frontmatter Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| name | string | yes | — | Agent identifier used in logs and subagent references |
| description | string | no | — | Human-readable description of the agent's purpose |
| model | string | no | (provider default) | Model selector (e.g., `sonnet`, `opus`, or full model ID) |
| provider | string | no | `anthropic` | API provider to use for the model |
| tools | list | no | `[]` | Tool categories to enable (see Tool Categories) |
| skills_directory | string | no | — | Path to a directory containing reloadable skill definitions |
| subagents_directory | string | no | — | Path to a directory containing subagent `.md` definitions |
| memory | boolean | no | `false` | Enable persistent memory across sessions |
| context_file | string | no | — | Path to a `CONTEXT.md` file injected into the system prompt |
| max_tokens | integer | no | — | Maximum context window size in tokens |
| task_management | boolean | no | `true` | Prepend Herald's shared task-management / tool-use guidance (TodoWrite discipline, parallel tool calls, objectivity) to the system prompt. Set to `false` (or `off`) to opt out when your agent's prompt deliberately defines its own workflow rules. |

## Tool Categories

Tools are organized into named categories. Listing a category in the `tools` field registers all tools in that category with the agent.

| Category | Tools Provided | Module Required |
|----------|---------------|-----------------|
| `filesystem` | `FileSystemTools` | herald-core |
| `shell` | `HeraldShellDecorator` | herald-core |
| `web` | WebTools (fetch + search) | herald-core |
| `skills` | `ReloadableSkillsTool` | herald-core |
| `subagents` | `TaskTool` + `TaskOutputTool` | herald-core |
| `memory` | `MemoryTools` | herald-persistence |
| `cron` | `CronTools` | herald-persistence |
| `telegram` | `TelegramSendTool` | herald-telegram |
| `gws` | `GwsTools` | herald-persistence |
| `ask` | `AskUserQuestionTool` | herald-core |
| `todo` | `TodoWriteTool` | herald-core |

### Special Values

- `tools: [all]` — register all available tools based on loaded modules
- `tools: []` — no tools (chat-only mode)

### Tool Category Details

**filesystem** — Read, write, list, and search files on the local filesystem.

**shell** — Execute shell commands via the Herald shell decorator, which enforces safety constraints.

**web** — Fetch URLs and perform web searches. Requires network access.

**skills** — Load and invoke reloadable skill definitions from `skills_directory`.

**subagents** — Spawn and coordinate subagent tasks from `subagents_directory`.

**memory** — Persist and retrieve keyed notes across agent sessions. Requires herald-persistence module.

**cron** — Schedule recurring tasks using cron expressions. Requires herald-persistence module.

**telegram** — Send messages to a configured Telegram chat. Requires herald-telegram module.

**gws** — Access Google Workspace APIs (Gmail, Calendar, Drive). Requires herald-persistence module.

**ask** — Prompt the user for input mid-task (interactive sessions only).

**todo** — Write structured TODO lists to a shared file for task tracking.

## Provider Configuration

| Provider | Env Var | Example Models |
|----------|---------|----------------|
| `anthropic` | `ANTHROPIC_API_KEY` | `claude-sonnet-4-5`, `claude-haiku-4-5`, `claude-opus-4-5` |
| `openai` | `OPENAI_API_KEY` | `gpt-4o`, `gpt-4o-mini` |
| `ollama` | `OLLAMA_BASE_URL` | `llama3.2`, `mistral`, etc. |
| `gemini` | `GEMINI_API_KEY` | `gemini-2.5-flash`, `gemini-2.0-pro`, etc. |

The `model` field accepts short aliases (e.g., `sonnet`, `opus`, `haiku`) which Herald resolves to the provider's current default for that tier, or a full model identifier string.

## Relationship to .claude/agents/*.md

`agents.md` is a superset of the subagent definition format used by Claude Code in `.claude/agents/*.md`. The core fields (`name`, `description`, `model`, `tools`) are compatible, with Herald adding:

- `provider` — multi-provider support beyond Anthropic
- `skills_directory` / `subagents_directory` — Herald-specific runtime paths
- `memory` — Herald persistent memory toggle
- `context_file` — injecting an external CONTEXT.md
- `max_tokens` — explicit context window control
- `task_management` — opt-out toggle for Herald's shared task-management / tool-use guidance

A file valid as a `.claude/agents/*.md` subagent definition is also a valid `agents.md` for Herald's ephemeral mode, though Herald-specific features will be absent unless the additional fields are present.

## Examples

### Minimal

The minimum required configuration — just a name and a system prompt.

```yaml
---
name: my-agent
description: Does things
---
You are a helpful agent.
```

### Chat-Only (No Tools)

An agent with no tool access, useful for pure conversational tasks.

```yaml
---
name: advisor
description: Provides advice without taking actions
model: sonnet
provider: anthropic
tools: []
---
You are a thoughtful advisor. You help users think through problems
but do not take actions on their behalf.
```

### Full Example

All available fields populated.

```yaml
---
name: full-featured-agent
description: Demonstrates every available configuration field
model: claude-sonnet-4-5
provider: anthropic
tools:
  - filesystem
  - shell
  - web
  - skills
  - subagents
  - memory
  - ask
  - todo
skills_directory: ./skills
subagents_directory: ./subagents
memory: true
context_file: ./CONTEXT.md
max_tokens: 100000
task_management: true
---
You are a fully equipped agent with access to the filesystem, shell,
web, skills, subagents, persistent memory, and user interaction tools.

Use your capabilities thoughtfully and confirm destructive operations
before executing them.
```

## Running an agents.md File

```bash
# Run once (ephemeral)
herald run path/to/my-agent.md

# Run with an initial prompt
herald run path/to/my-agent.md --prompt "Analyze the logs in ./logs/"

# Run interactively
herald run path/to/my-agent.md --interactive
```

## Validation

Herald validates frontmatter at load time and will report:

- Missing required fields (`name`)
- Unknown tool category names
- Unrecognized provider values
- Non-existent paths for `skills_directory`, `subagents_directory`, and `context_file`

Unknown frontmatter keys are ignored to allow forward compatibility.
