<p align="center">
  <img src="assets/banner.jpg" alt="Herald — Personal AI Assistant" width="100%">
</p>

# Herald

**Personal AI Assistant** — a single-user, always-on AI agent that lives in Telegram and runs 24/7 on your Mac.

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Java](https://img.shields.io/badge/Java-21-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0--SNAPSHOT-blueviolet.svg)

> An AI agent that knows who you are, runs on your machine, can do things on your behalf, and reaches out to you — not just the other way around.

## About

Most AI assistants are stateless chat windows — you ask, they answer, they forget. Herald is different. It's a personal AI agent that runs continuously on your Mac, connects to you through Telegram, and builds a persistent understanding of who you are, what you care about, and what you need.

Herald can execute shell commands, manage your calendar and email, run scheduled tasks like morning briefings, and delegate complex research to specialized subagents — all while maintaining a growing memory of your preferences and context.

## Features

- **Telegram-native** — chat with your AI assistant where you already message
- **Persistent memory** — remembers your context, preferences, and history across sessions
- **Skills system** — extensible via Markdown files in `skills/` directory
- **Subagent delegation** — routes complex research to specialist agents via TaskTool
- **Proactive scheduling** — morning briefings, reminders, and cron-driven outreach
- **Shell & file access** — executes commands on your Mac with security guardrails
- **Google Workspace** — Gmail and Google Calendar via `gws` CLI
- **Multi-provider** — Anthropic, OpenAI, Ollama, and Gemini models, switchable at runtime
- **Obsidian integration** — cold memory storage, session archival, and research notes in an Obsidian vault
- **Management console** — Vue 3 web UI for skills editing, memory, cron, and status

## Skills

Skills are Markdown files with YAML front matter that teach Herald new capabilities without code changes. Drop a file into `skills/` and Herald picks it up immediately — no restart required.

```
skills/
├── broadcom/SKILL.md        # VMware/Broadcom knowledge base
├── github/SKILL.md          # GitHub workflow automation
├── gmail/SKILL.md           # Email composition & search
├── google-calendar/SKILL.md # Calendar management
├── google-drive/SKILL.md    # Drive file operations
├── obsidian/SKILL.md        # Obsidian vault integration
└── weather/SKILL.md         # Weather lookups
```

Each skill file follows this format:

```markdown
---
name: skill-name
description: What this skill does (shown to the LLM for selection)
---

Instructions, context, and examples that guide Herald's behavior
when this skill is activated by the agent.
```

Herald's `ReloadableSkillsTool` wraps the upstream `SkillsTool` with a `WatchService`-based filesystem watcher (`SkillsWatcher`) that triggers a 250ms debounced reload on any file change. The Herald Console also provides a web-based skills editor with live reload status via SSE.

## Architecture

```mermaid
flowchart LR
    A["Telegram"] -->|long poll| B["herald-bot<br/>(Spring Boot)"]
    B -->|ChatClient| C["Claude API<br/>(Anthropic)"]
    B -->|TaskTool| D["Subagents<br/>(Research, Explore, Plan)"]
    B -->|MCP Client| E["Google Calendar<br/>Gmail"]
    B -->|JDBC| F[("SQLite<br/>~/.herald/herald.db")]
    G["Herald Console<br/>(Vue 3)"] -->|REST API| H["herald-ui<br/>(Spring Boot)"]
    H -->|read| F
```

Herald is a Spring Boot monorepo producing two runnable JARs:

| Service | Role | Port |
|---------|------|------|
| **herald-bot** | Telegram bot, agent loop, tools, cron | 8081 |
| **herald-ui** | Management console (REST API + Vue 3) | 8080 |

Both run as macOS `launchd` services and share a single SQLite database.

## Data Flow

```mermaid
sequenceDiagram
    participant T as Telegram
    participant P as TelegramPoller
    participant A as ChatClient (Herald)
    participant Tools as Tools & Skills
    participant DB as SQLite

    T->>P: Incoming message
    P->>P: Auth check (allowed_chat_id)
    P->>A: User message + memory + history
    A->>Tools: Tool calls (shell, memory, web, etc.)
    Tools-->>A: Tool results
    A->>A: Loop until end_turn
    A-->>P: Final response
    P-->>T: Send reply
    A->>DB: Persist history + memory
```

## Getting Started

This guide walks you through setting up Herald from scratch — from creating a Telegram bot to running your first conversation.

### Prerequisites

Before you begin, make sure you have the following installed:

| Requirement | Version | Check Command |
|-------------|---------|---------------|
| **macOS** | Any recent version | — |
| **Java JDK** | 21+ | `java -version` |
| **Maven** | 3.9+ (or use included wrapper) | `./mvnw -version` |
| **Node.js** | 20+ | `node -v` |
| **npm** | 10+ | `npm -v` |

You will also need:

- **Anthropic API Key** — sign up at [console.anthropic.com](https://console.anthropic.com)
- **Telegram Bot Token** — create one via [@BotFather](https://t.me/BotFather) on Telegram

### Step 1: Create a Telegram Bot

1. Open Telegram and search for [@BotFather](https://t.me/BotFather)
2. Send `/newbot` and follow the prompts to name your bot
3. Copy the **bot token** BotFather gives you (you'll need this later)
4. Send a message to your new bot, then visit `https://api.telegram.org/bot<YOUR_TOKEN>/getUpdates` to find your **chat ID** in the response JSON

### Step 2: Clone and Build

```bash
git clone https://github.com/dbbaskette/herald.git
cd herald
./mvnw package -DskipTests
```

This builds both the `herald-bot` and `herald-ui` JARs.

### Step 3: Configure Environment

```bash
cp .env.example .env
```

Open `.env` and fill in the three required values:

```bash
ANTHROPIC_API_KEY=sk-ant-...
HERALD_TELEGRAM_BOT_TOKEN=123456:ABC-DEF...
HERALD_TELEGRAM_ALLOWED_CHAT_ID=your-chat-id
```

The `.env.example` file documents all optional variables (additional AI providers, Google Workspace, agent behavior, etc.). The `.env` file is gitignored so your secrets stay local.

### Step 4: Run Herald

**Using the run script** (recommended — auto-loads `.env`):

```bash
./run.sh          # starts both bot + ui (default)
./run.sh bot      # starts herald-bot only (port 8081)
./run.sh ui       # starts herald-ui only (port 8080)
./run.sh stop     # stops all herald services
./run.sh build    # builds all modules
```

**Or manually:**

```bash
source .env
make dev
```

Herald will start polling Telegram for messages. Open your bot in Telegram and send a message — you should get a response from Claude.

**Verify it's working:**

- Send `/status` in Telegram to see system info
- Send `/help` to see all available commands
- Try a natural language message like "What can you do?"

### Step 5: Install as a Service (Optional)

To run Herald 24/7 as a background service:

```bash
# Check that required env vars are set
make check-env

# Build, install, and start the launchd service
make install
```

This installs Herald as a macOS `launchd` agent that starts automatically on login.

Manage the service with:

```bash
make start       # Start the service
make stop        # Stop the service
make restart     # Restart the service
make logs        # Tail the log file
make uninstall   # Remove the service
```

Logs are written to `~/Library/Logs/herald.log`.

### Step 6: Set Up the Management Console (Optional)

The Herald Console provides a web UI for managing skills, memory, cron jobs, and viewing conversation history:

```bash
# Build the Vue 3 frontend
cd herald-ui/frontend && npm install && npm run build && cd ../..

# Run the console
./mvnw -pl herald-ui spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080) in your browser.

### Step 7: Add Google Workspace Integration (Optional)

Herald supports Gmail and Google Calendar via the [Google Workspace CLI (`gws`)](https://www.npmjs.com/package/@googleworkspace/cli). See **[docs/gws-setup.md](docs/gws-setup.md)** for installation and authentication instructions.

## Environment Variables

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `ANTHROPIC_API_KEY` | Anthropic API key | Yes | — |
| `HERALD_TELEGRAM_BOT_TOKEN` | Bot token from @BotFather | Yes | — |
| `HERALD_TELEGRAM_ALLOWED_CHAT_ID` | Your Telegram chat ID | Yes | — |
| `OPENAI_API_KEY` | OpenAI API key | No | — |
| `OLLAMA_BASE_URL` | Ollama server URL | No | — |
| `GEMINI_API_KEY` | Google Gemini API key | No | — |
| `GOOGLE_WORKSPACE_CLI_CLIENT_ID` | OAuth client ID for Gmail/Calendar | No | — |
| `GOOGLE_WORKSPACE_CLI_CLIENT_SECRET` | OAuth client secret | No | — |
| `HERALD_WEB_SEARCH_API_KEY` | Brave Search API key | No | — |
| `HERALD_CRON_TIMEZONE` | Timezone for cron scheduler | No | `America/New_York` |
| `HERALD_AGENT_PERSONA` | Override agent persona | No | Built-in default |
| `HERALD_AGENT_CONTEXT_FILE` | Path to standing brief | No | `~/.herald/CONTEXT.md` |
| `HERALD_WEATHER_LOCATION` | Location for weather tool | No | — |
| `HERALD_AGENT_MAX_CONTEXT_TOKENS` | Token limit before context compaction | No | `200000` |
| `HERALD_CONFIG` | Override config file path | No | `~/.herald/herald.yaml` |

## Telegram Commands

| Command | Description |
|---------|-------------|
| `/help` | Show all available commands |
| `/status` | System status: uptime, model, MCP connections |
| `/memory list` | Display all stored memory entries |
| `/memory set <key> <value>` | Manually set a memory entry |
| `/memory clear` | Clear all memory (with confirmation) |
| `/skills list` | Show all loaded skills |
| `/skills reload` | Force reload skills from disk |
| `/cron list` | List all cron jobs with schedules |
| `/cron enable/disable <name>` | Toggle a cron job |
| `/model <provider> <model>` | Switch model at runtime |
| `/model status` | Show current provider and model |
| `/debug` | Context size, memory count, tools count |
| `/reset` | Clear conversation history (not memory) |

## Project Structure

```
herald/
├── pom.xml                          # Parent POM — Spring AI BOM
├── Makefile                         # Build, install, service management
├── herald-bot/                      # Core bot service
│   └── src/main/java/com/herald/
│       ├── HeraldApplication.java
│       ├── agent/                   # ChatClient config, agent service
│       ├── telegram/                # Poller, commands, formatting
│       ├── memory/                  # Memory tools (@Tool beans)
│       ├── tools/                   # Shell decorator, web tools
│       ├── cron/                    # Cron service, briefing jobs
│       └── config/                  # Configuration properties
├── herald-ui/                       # Management console
│   ├── src/main/java/com/herald/ui/
│   │   ├── api/                     # REST controllers
│   │   └── sse/                     # SSE status stream
│   └── frontend/                    # Vue 3 + Vite
├── skills/                          # Reloadable skill definitions
├── .claude/
│   └── agents/                      # Subagent definitions (*.md)
├── com.herald.plist                 # launchd service definition (bot)
├── com.herald-ui.plist              # launchd service definition (UI)
└── docs/
    ├── gws-setup.md                 # Google Workspace CLI setup guide
    └── obsidian-setup.md            # Obsidian vault integration guide
```

## Agentic Patterns — Spring AI Agent Utils

Herald is a reference implementation of the agentic patterns described in the [Spring AI Agentic Patterns](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/) blog series by Christian Tzolov. The series documents the [spring-ai-agent-utils](https://github.com/spring-ai-community/spring-ai-agent-utils) toolkit — a set of composable building blocks for AI agents, inspired by Claude Code's architecture. Herald adopts all five patterns, adapting each for a Telegram-native, always-on personal assistant.

### The Pattern

The core idea is that truly agentic behavior emerges from composition — not from a single monolithic prompt, but from a set of small, focused tools and advisors that the LLM orchestrates through its tool-calling loop:

```mermaid
flowchart TB
    subgraph Agent Loop
        A["ChatClient.prompt()"] --> B["Advisor Chain<br/>(DateTime → Context → Memory → History)"]
        B --> C["LLM Call"]
        C --> D{Tool calls?}
        D -->|yes| E["Execute Tools"]
        E --> C
        D -->|no| F["Return Response"]
    end

    subgraph Tools
        G["SkillsTool"]
        H["TaskTool / TaskOutputTool"]
        I["AskUserQuestionTool"]
        J["TodoWriteTool"]
        K["Shell / FileSystem / Web"]
    end

    E --> Tools
```

### How Herald Applies Each Pattern

| Pattern | Blog Post | Herald Implementation |
|---------|-----------|----------------------|
| **Agent Skills** | [Part 1: Modular, Reusable Capabilities](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/) | `ReloadableSkillsTool` wraps the library's `SkillsTool` with hot-reload via `WatchService`. Skills live in `skills/` as Markdown files with YAML front matter. File changes trigger a 250ms debounced reload — no restart needed. |
| **AskUserQuestion** | [Part 2: Agents That Clarify Before Acting](https://spring.io/blog/2026/01/16/spring-ai-ask-user-question-tool/) | Upstream `AskUserQuestionTool` from spring-ai-agent-utils, backed by `TelegramQuestionHandler` implementing `QuestionHandler`. Structured questions with options render as Telegram inline keyboard buttons (single-select); multi-select and free-text fall back to text-based messaging. Blocks on a `CompletableFuture` with a 5-minute timeout. |
| **TodoWrite** | [Part 3: Why Your AI Agent Forgets Tasks](https://spring.io/blog/2026/01/20/spring-ai-agentic-patterns-3-todowrite) | Upstream `TodoWriteTool` from spring-ai-agent-utils with structured task states (`pending`, `in_progress`, `completed`). A `todoEventHandler` bridges to `TodoProgressEvent` → `TodoProgressListener` → Telegram, showing real-time progress with status symbols as each step completes. |
| **Subagent Orchestration** | [Part 4: Subagent Orchestration](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents) | `TaskTool` and `TaskOutputTool` from the library, wired with multi-model routing. Three subagents defined in `.claude/agents/*.md` — **explore** (Sonnet, read-only), **plan** (Sonnet, architecture), **research** (Opus, deep analysis) — each with tailored tool subsets and model tiers. |
| **A2A Protocol** | [Part 5: Agent2Agent Interoperability](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration/) | Not yet adopted — planned for cross-agent communication. |

### Advisor Chain — Dynamic Context Injection

Where the blog series describes `AgentEnvironment` for injecting runtime context, Herald extends this with a chain of Spring AI `CallAdvisor` implementations that enrich every prompt:

| Advisor | What It Injects |
|---------|-----------------|
| `DateTimePromptAdvisor` | Current date/time and timezone into system prompt placeholders |
| `ContextMdAdvisor` | Standing brief from `~/.herald/CONTEXT.md`, re-read on every turn |
| `MemoryBlockAdvisor` | All persistent key/value memories from SQLite |
| `ContextCompactionAdvisor` | Auto-compacts conversation history when approaching token limits |
| `OneShotMemoryAdvisor` | JDBC-backed conversation history (windowed, 100 messages). Custom replacement for Spring AI's `MessageChatMemoryAdvisor` — loads/saves history once per request instead of on every tool-call iteration, preventing exponential message growth. |

### Tool Registration Architecture

Herald separates tools into two categories matching how Spring AI handles them:

- **`@Tool`-annotated POJOs** (via `.defaultTools()`) — `MemoryTools`, `HeraldShellDecorator`, `FileSystemTools`, `WebTools`, `AskUserQuestionTool` (upstream), `TodoWriteTool`, `CronTools`, `GwsTools`, `TelegramSendTool`
- **Raw `ToolCallback` objects** (via `.defaultToolCallbacks()`) — `TaskTool`, `TaskOutputTool`, `ReloadableSkillsTool` from spring-ai-agent-utils

This mirrors the library's design: custom domain tools as annotated beans, library-provided tools as pre-built callbacks.

### Shell Security Model

Herald's `HeraldShellDecorator` wraps shell execution with layered protections:

- **Blocklist** — configurable regex patterns block destructive commands (`rm -rf /`, `mkfs`, `dd`, etc.) outright
- **Confirmation gate** — commands requiring `sudo`, piping to a shell, or writing to system directories trigger a Telegram confirmation prompt. The user must reply `/confirm <id> yes` within a configurable timeout before the command executes.
- **Sensitive redaction** — API keys, Bearer tokens, and passwords are redacted in logs
- **Timeout** — all commands are killed after a configurable timeout (default 30s)

### Runtime Model Switching

Herald supports switching between AI providers and models at runtime via the `/model` Telegram command or the management console. The active override is persisted in the `settings` table so it survives restarts. Supported providers:

| Provider | Default Model | Config Property |
|----------|---------------|-----------------|
| Anthropic | claude-sonnet-4-5 | `spring.ai.anthropic.chat.options.model` |
| OpenAI | gpt-4o | `herald.agent.model.openai` |
| Ollama | llama3.2 | `herald.agent.model.ollama` |
| Gemini | gemini-2.5-flash | `herald.agent.model.gemini` |

## Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Java 21 (virtual threads) |
| Framework | Spring Boot 4.0.x |
| AI Framework | Spring AI 2.0.0-SNAPSHOT |
| Agent Utils | spring-ai-agent-utils |
| Telegram | pengrad/java-telegram-bot-api |
| Database | SQLite (WAL mode) |
| Console Frontend | Vue 3 + Vite + Tailwind CSS |
| Console Backend | Spring MVC + SSE |
| Process Management | macOS launchd |

## Database Schema

```mermaid
erDiagram
    messages {
        int id PK
        text role
        text content
        text tool_calls
        datetime created_at
    }
    memory {
        int id PK
        text key UK
        text value
        datetime updated_at
    }
    cron_jobs {
        int id PK
        text name UK
        text schedule
        text prompt
        datetime last_run
        int enabled
        int built_in
    }
    commands {
        int id PK
        text type
        text payload
        text status
        datetime created_at
        datetime completed_at
    }
    model_usage {
        int id PK
        text subagent_id
        text provider
        text model
        int tokens_in
        int tokens_out
        datetime created_at
    }
    model_overrides {
        int id PK
        text provider
        text model
        datetime updated_at
    }
    settings {
        text key PK
        text value
        datetime updated_at
    }
    SPRING_AI_CHAT_MEMORY {
        text conversation_id
        text content
        text type
        datetime timestamp
    }
```

## Build Phases

| Phase | Focus | Key Deliverable |
|-------|-------|-----------------|
| 1 | Core Loop + Spring AI Foundation | Telegram ↔ Claude ↔ Tools end-to-end |
| 2 | Subagents + Multi-Provider | TaskTool delegation, Ollama support |
| 3 | Skills & Memory | SkillsTool hot-reload, CONTEXT.md, auto-memory |
| 4 | Proactive & MCP | Cron jobs, morning briefing, Calendar/Gmail |
| 5 | Herald Console | Vue 3 management UI with skills editor |
| 6 | Polish | Voice, vision, Docker sandbox, dark mode |

## Contributing

Contributions are welcome! To get started:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

Distributed under the MIT License. See [LICENSE](LICENSE) for details.
