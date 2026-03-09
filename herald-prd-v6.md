# Herald — Personal AI Assistant
**Product Requirements Document v6**

> Spring AI Agentic Patterns Architecture · March 2026 · DRAFT

---

| OWNER Dan | STATUS Draft — Not Yet Built |
| --- | --- |
| PLATFORM Telegram + Web UI → Go services → Claude API | TARGET RUNTIME macOS (always-on Mac, LAN-accessible UI) |
| SCOPE Single-user, personal assistant | INSPIRED BY OpenClaw (simplified) |

## 1. Executive Summary

Herald is a single-user, personal AI assistant that lives in Telegram and runs 24/7 on a Mac. It is inspired by OpenClaw but built from scratch in Java — dramatically simpler, with no multi-agent routing, no channel marketplace, no companion app, and no CLI management layer.

The core bet: all the value of a persistent AI assistant comes from four primitives — a reliable message loop, memory that persists across sessions, a skills system for extensibility, and proactive scheduling. Everything else in OpenClaw is orchestration overhead for a multi-user, multi-agent product. Herald strips that away and exposes the primitives directly.

| The One-Line Pitch An AI agent that knows who you are, runs on your machine, can do things on your behalf, and reaches out to you — not just the other way around. |
| --- |

## 2. Problem Statement

### 2.1 What OpenClaw Gets Right

OpenClaw proves the concept: a persistent, tool-using AI assistant accessible from a chat app you already use is genuinely transformative. The social proof is real — people are using it to run companies, manage email, write code, and control smart home devices.

### 2.2 What OpenClaw Gets Wrong (For This Use Case)

OpenClaw's complexity is its greatest weakness for a personal, single-user setup:

- Installation requires Node.js, npm, a tunnel (ngrok/Cloudflare), webhook configuration, and daemon management
- The gateway abstraction layer adds operational overhead that serves multi-user scenarios but is unnecessary for one person
- CLI management commands (openclaw gateway status, openclaw memory list, etc.) are a second interface on top of Telegram — defeating the purpose
- The skills marketplace (ClawdHub) is useful but adds complexity; skills should just be files in a folder
- Multi-agent routing, multi-gateway support, formal security verification — all overhead for personal use
| Root Cause OpenClaw is architected as a platform product. Herald is architected as a personal tool. These require fundamentally different decisions at every layer. |
| --- |

## 3. Goals & Non-Goals

### 3.1 Goals

- Telegram is the only interface — no web UI, no CLI chat, no other channels
- Full persistent memory — the assistant knows your context, preferences, and history across all sessions
- Shell execution and file system access — the assistant can actually do things on the Mac
- Skills system — extensible via markdown files in a folder; no marketplace, no binary gates
- Proactive heartbeats — scheduled messages and morning briefings without waiting to be asked
- MCP integration — calendar and email via MCP servers over HTTP/SSE
- Single Go binary — drop it in, point at a config file, run it as a launchd service
- Zero runtime dependencies beyond the binary and a config file
### 3.2 Non-Goals

- Multi-user support (this is explicitly a personal tool)
- Multiple channel adapters (Telegram only)
- Web UI, TUI, or companion app
- CLI management commands (the bot manages itself via Telegram)
- Multi-agent routing or subagent orchestration
- Skills marketplace or community hub
- Formal security verification or sandboxing
- WhatsApp, Discord, Slack, iMessage, or any other messaging platform
- Mobile app or native UI of any kind

## 4. Architecture

### 4.1 System Overview

Herald is a Spring Boot monorepo producing two runnable JARs: herald-bot and herald-ui (the Console). Both run as macOS launchd services and share a single SQLite database. The architecture follows the Spring AI Agentic Patterns series (spring-ai-agent-utils) closely: a single main ChatClient with TaskTool for subagent delegation, SkillsTool for on-demand skill loading, TodoWriteTool for multi-step task tracking, and AskUserQuestionTool for interactive clarification — all from the spring-ai-agent-utils library.

| Layer | Responsibility | Technology |
| --- | --- | --- |
| Transport | Telegram long-polling, message auth, media handling | pengrad/java-telegram-bot-api |
| Main Agent | Single ChatClient with full tool set + subagent delegation | Spring AI ChatClient + TaskTool + ToolCallAdvisor |
| Subagents | Specialist agents in isolated context windows (research, plan, etc.) | TaskTool + ClaudeSubagentReferences (.claude/agents/*.md) |
| Skills | On-demand modular capabilities via progressive disclosure | SkillsTool + FileSystemTools + ShellTools (spring-ai-agent-utils) |
| Task Tracking | Explicit multi-step todo list for complex workflows | TodoWriteTool (spring-ai-agent-utils) |
| Interactive Q&A | Agent asks clarifying questions before acting | AskUserQuestionTool → Telegram QuestionHandler |
| Model Providers | Abstraction over Anthropic, OpenAI, Ollama | Spring AI ChatClient — provider-agnostic |
| Persistence | Memory, sessions, cron state, IPC commands | SQLite via JdbcChatMemoryRepository + JDBC |
| Console (herald-ui) | REST API + Vue 3 management UI | Spring MVC + SSE + Vite |

### 4.2 Multi-Agent Design — TaskTool + Subagents

Herald uses a single main ChatClient as the primary agent. When the main agent encounters a task that warrants specialist handling — deep research, code analysis, planning — it delegates via TaskTool to a named subagent. Each subagent runs in its own isolated context window, using only the tools and model it needs, and returns just its findings to the main agent. Subagents are defined as Markdown files in .claude/agents/ — no Java code required to add, change, or tune them.

| Attribute | Main Agent | Research Subagent |
| --- | --- | --- |
| Attribute | Main Agent (Herald) | Research Subagent (Research Agent) |
| Role | Default daily driver — handles all messages | Deep research, multi-source analysis, long-form synthesis |
| Model default | claude-sonnet-4-5 (or Ollama) | claude-opus-4-5 |
| Tools | All tools + TaskTool for delegation | Read, Grep, Glob, WebTools, ShellTools (restricted) |
| Context window | Shared conversation thread with memory | Isolated per-task — only sees the task prompt + its own work |
| Invocation | Always active | Via TaskTool: explicit or model-decided |
| Returns | N/A — always on | Summary/findings only — no raw intermediate steps |
| Definition | ChatClient bean in Java config | .claude/agents/research.md frontmatter + system prompt |

| Why subagent files, not second ChatClient bean? The old design hardcoded two ChatClient beans and a custom router (~80 lines of Java). TaskTool replaces all of that. Adding a new specialist (a code-reviewer, a calendar-planner, a data-analyst) means dropping a new .md file in .claude/agents/ — zero Java code. The main agent reads all agent descriptions at startup and decides delegation automatically. |
| --- |

### 4.3 Subagent Definitions (.claude/agents/)

Subagents are Markdown files with YAML frontmatter stored in .claude/agents/. The TaskTool reads them at startup and exposes their names and descriptions to the main agent. The main agent delegates based on description matching — no routing code required.

```
# .claude/agents/research.md
---
name: research
description: Deep research agent. Use for multi-source analysis, comprehensive
reports, fact-checking, or tasks requiring 10+ minutes of focused
research. Invoke when depth is more important than speed.
tools: Read, Grep, Glob, Bash, web_search, web_fetch
model: opus
---
```
```
You are Research Agent. Dan's deep research and reasoning agent.
Thorough, measured, deferential. When your task is complete,
return a structured summary to the main agent.
```
#### Built-in Subagents (Phase 2)

Spring AI Agent Utils provides four built-in subagents available automatically when TaskTool is configured: Explore (fast read-only codebase exploration), General-Purpose (full read/write multi-step research), Plan (software architect for design strategies), and Bash (command execution specialist). Herald adds a custom Research subagent (Research Agent persona, Opus model, web tools).

| Multi-Model Routing Subagents specify their preferred model in frontmatter: model: haiku / sonnet / opus. TaskToolCallbackProvider maps these to named ChatClient builders. Route simple tasks to cheaper models; route deep research to Opus. Configure additional builders as needed in HeraldConfig. |
| --- |

### 4.4 Model Provider Abstraction

Spring AI's ChatClient is the provider abstraction. The main agent ChatClient is built once from a named ChatClient.Builder. Subagents declare their preferred model in frontmatter (haiku / sonnet / opus); TaskToolCallbackProvider maps these to named builders. Switching a subagent's model means editing the .md file — no Java changes.

```
// Main agent — single ChatClient bean
@Bean
ChatClient mainClient(ChatClient.Builder builder, ChatMemory memory,
TaskToolCallbackProvider taskTools,
SkillsTool skillsTool, FileSystemTools fsTools,
ShellTools shellTools, TodoWriteTool todoTool,
AskUserQuestionTool askTool) {
return builder
.defaultToolCallbacks(taskTools, skillsTool)
.defaultTools(fsTools, shellTools, todoTool, askTool)
.defaultAdvisors(
ToolCallAdvisor.builder().conversationHistoryEnabled(false).build(),
MessageChatMemoryAdvisor.builder(memory).build()
)
.defaultSystem(ultronSystemPrompt)
.build();
}
```
Three provider starters ship at launch:

| Starter | Details |
| --- | --- |
| spring-ai-starter-model-anthropic | Powers the main agent (Sonnet) and research subagent (Opus). Native tool use, extended thinking for research, prompt caching for repeated system prompt + memory prefix. |
| spring-ai-starter-model-openai | Single starter covering both OpenAI and Ollama. Both use the OpenAI /v1/chat/completions format. Configured by spring.ai.openai.base-url. |

### 4.5 Request Flow

Every inbound Telegram message follows this path:

- Message arrives via long-polling, authenticated against allowed chat ID
- Slash commands (/help, /memory, etc.) handled directly — no agent loop
- Main ChatClient assembles context: MessageChatMemoryAdvisor injects history + memory from SQLite
- SkillsTool description registry embedded in tool definition — LLM sees all skill names/descriptions without loading full content
- ChatClient.call() enters ToolCallAdvisor loop: model responds → tools dispatched → results appended → loop until no tool calls
- If task is complex (3+ steps), model invokes TodoWriteTool to create a task list before starting; marks each task in_progress → completed sequentially
- If clarification needed, model invokes AskUserQuestionTool → TelegramQuestionHandler sends options to Telegram, blocks on CompletableFuture until Dan replies, returns answers to model
- If delegation needed, model invokes TaskTool with subagent name + task prompt → subagent runs in isolated context, returns findings
- Final text response sent to Telegram
- JdbcChatMemoryRepository persists updated history to SQLite; memory tools update key-value store
### 4.6 Polling vs Webhooks

| Decision: Long Polling Herald uses Telegram long polling rather than webhooks. Webhooks require a publicly reachable HTTPS endpoint — a tunnel or cloud VM. Long polling works from behind NAT with zero infrastructure. For a personal tool on a Mac, polling is the right call. Latency difference is negligible (~1-2s). |
| --- |

### 4.7 Data Model

All state lives in a single SQLite file at ~/.herald/herald.db. Note: in-flight task lists (multi-step todos) are managed by TodoWriteTool in chat memory — they do not need a separate database table.

| Table | Schema | Purpose |
| --- | --- | --- |
| messages | id, role, content, tool_calls, created_at | Shared conversation history with full tool call records |
| memory | id, key, value, updated_at | Shared persistent key-value store — facts Dan wants Herald to remember |
| cron_jobs | id, name, schedule, prompt, last_run, enabled | Scheduled agent runs for proactive messaging |
| commands | id, type, payload, status, created_at, completed_at | IPC bus between herald-ui and the bot |
| model_usage | id, subagent_id, provider, model, tokens_in, tokens_out, created_at | Per-turn cost tracking; subagent_id null for main agent turns |

## 5. Core Features

### 5.1 Telegram Transport

#### 5.1.1 Authentication

Herald is single-user. The bot only responds to messages from a configured Telegram chat ID. All other senders receive no response and are not logged. This is enforced at the transport layer before any processing occurs.

#### 5.1.2 Message Handling

- Text messages → fed to agent loop
- Photos/images → sent to Claude as vision input
- Voice messages → transcribed via Whisper API, then fed to agent loop
- Documents/files → saved to ~/.herald/inbox/, then path passed to agent
- Telegram commands (/help, /memory, /skills, /cron) → handled directly without agent loop
#### 5.1.3 Response Handling

- Responses longer than 4096 chars are split at sentence boundaries (Telegram limit)
- Markdown formatting preserved where Telegram supports it
- Typing indicator shown during agent processing
- Tool execution steps shown as Telegram status messages (ephemeral, deleted after final response)
### 5.2 Agent Loop

#### 5.2.1 Model

Claude Sonnet 4.5 via the Anthropic API. Model is configurable in herald.yaml. The agent loop is a standard tool-use loop: send message → receive response → if tool_use block present, execute tool → append result → repeat until stop_reason is end_turn.

#### 5.2.2 Context Assembly

On each turn, the context window is assembled in this order:

- System prompt (identity + capabilities + current date/time)
- Memory block (all key-value pairs from memory table, formatted as markdown)
- Skills block (all active SKILL.md files, injected as XML list per AgentSkills spec)
- Conversation history (last N messages from SQLite, up to ~80k tokens)
- Current user message
#### 5.2.3 Context Management

When conversation history approaches the context limit, older messages are summarized and the summary replaces them. The summary itself becomes a memory entry so context is never silently lost.

### 5.3 Memory System

Memory is how Herald becomes uniquely yours over time. It is NOT a RAG system — it is a structured key-value store of facts that get injected directly into every system prompt.

#### 5.3.1 Memory Operations

The model can call memory tools during any turn:

- memory_set(key, value) — store or update a fact
- memory_get(key) — retrieve a specific fact
- memory_list() — list all stored keys
- memory_delete(key) — remove a fact
#### 5.3.2 Auto-Memory

The system prompt instructs the model to proactively store facts it learns about the user — preferences, ongoing projects, recurring context — without being explicitly asked. The model decides what's worth storing.

#### 5.3.3 Memory Telegram Commands

- /memory list — shows all stored memory keys and values
- /memory clear — clears all memory (with confirmation prompt)
- /memory set <key> <value> — manually set a memory entry
### 5.4 Skills System

#### 5.4.1 What Skills Are

Skills are modular folders of instructions, scripts, and resources stored in .claude/skills/<skill-name>/SKILL.md. Each skill teaches the model how to perform a domain-specific task. Skills use progressive disclosure: at startup only the name and description are loaded into the SkillsTool registry. The model reads the full SKILL.md only when it decides a task matches — keeping the context window lean regardless of how many skills are registered.

| Skills directory: .claude/skills/ (not ~/.herald/skills/) Herald uses .claude/skills/ — the same path convention as Claude Code. This means skills are portable: any Claude Code skill works in Herald out of the box, and skills you author for Herald also work in Claude Code sessions. |
| --- |

#### 5.4.2 Skill Format

```
# .claude/skills/github-pr-review/SKILL.md
---
name: github-pr-review
description: Reviews GitHub pull requests: runs gh CLI, summarizes diff,
provides actionable feedback. Use when asked to review a PR.
---
```
```
## Instructions
When asked to review a PR:
1. Run: gh pr view <number> --json title,body,files
2. Fetch the diff: gh pr diff <number>
3. Summarize changes and provide specific actionable feedback.
```
#### 5.4.3 How Skills Load — Three Steps

- Discovery (startup): SkillsTool scans .claude/skills/, parses YAML frontmatter, builds lightweight registry embedded in the tool description. Zero full SKILL.md content in context.
- Activation (during conversation): LLM sees skill names/descriptions in the tool definition. When a request matches, the LLM calls the Skill tool with the skill name.
- Execution (on invocation): SkillsTool loads the full SKILL.md from disk and returns it to the LLM. LLM follows instructions; uses FileSystemTools Read or ShellTools Bash to access bundled scripts/references on demand. Script code never enters the context window — only output does.
#### 5.4.4 Skills Management

- Hot-reload: WatchService monitors .claude/skills/ — new or updated skills available within 250ms, no restart needed
- /skills list — shows all active skills with name and description
- /skills reload — manually trigger reload
- The model can author new skills itself (write SKILL.md files to .claude/skills/)
- Skills can bundle helper scripts in scripts/ and reference docs in references/ — loaded on demand
### 5.5 Tool Dispatch

#### 5.5.1 spring-ai-agent-utils Tools (Library)

Herald uses spring-ai-agent-utils (org.springaicommunity:spring-ai-agent-utils) as the primary tool implementation. These replace custom-built equivalents and are configured as Spring beans:

| Tool | Description |
| --- | --- |
| SkillsTool | Discovers and loads skills from .claude/skills/ on demand. Embeds skill registry (names + descriptions) into tool definition for progressive disclosure. Requires FileSystemTools + ShellTools to work. |
| FileSystemTools | Read, list, and navigate files. Used by skills to load reference files and by the model for direct file access. Replaces custom FileTools.java. |
| ShellTools | Execute shell commands (Bash function). Scripts in skill bundles execute through here. Replaces custom ShellTools.java — but Herald wraps it with the security blocklist. |
| TodoWriteTool | LLM-driven task list for multi-step workflows. Enforces one-in-progress constraint. Publishes ApplicationEvents for real-time Telegram progress updates. Self-governing: LLM invokes it when tasks >= 3 steps. |
| AskUserQuestionTool | LLM asks structured clarifying questions before acting. Each question has header, 2-4 options, single/multi-select flag, plus free-text input. Herald wires a TelegramQuestionHandler. |
| TaskTool / TaskToolCallbackProvider | Subagent delegation. Main agent invokes Task tool with subagent name + prompt; subagent runs in isolated context window and returns findings. Replaces AgentRegistry + AgentRouter. |

#### 5.5.2 Herald-Specific @Tool Beans

These tools are Herald-specific and not in the library:

| Tool Signature | Description |
| --- | --- |
| memory_set(key, value) | Store a fact in persistent SQLite memory (shared across sessions). |
| memory_get(key) | Retrieve a fact from persistent memory. |
| memory_list() | List all memory keys and values. |
| memory_delete(key) | Delete a memory entry. |
| telegram_send(message) | Send a proactive Telegram message (used by cron jobs). |
| web_fetch(url) | Fetch a URL and return text content. |
| web_search(query) | Search the web and return top results with snippets. |

### 5.5.3 AskUserQuestionTool — Telegram Integration

AskUserQuestionTool needs a QuestionHandler implementation to present questions to Dan and collect answers. Herald implements a TelegramQuestionHandler that bridges the synchronous QuestionHandler API with async Telegram messaging:

```
@Component
public class TelegramQuestionHandler {
```
```
// Called by AskUserQuestionTool when the LLM has questions
public Map<String, String> handle(List<Question> questions) {
// 1. Format each question as a numbered Telegram message
// 2. Block on CompletableFuture<Map<String,String>>
// 3. TelegramPoller resolves the future when Dan replies
return future.get(5, MINUTES); // timeout → empty answers
}
}
```
The model uses AskUserQuestionTool before performing ambiguous or high-impact actions: scheduling meetings ('which calendar?'), drafting emails ('what tone?'), making purchases, or any task where wrong assumptions would require rework.

### 5.5.4 TodoWriteTool — Task Visibility

When the model receives a complex multi-step task, it calls TodoWriteTool to decompose it before starting. Progress is published as ApplicationEvents and forwarded to Telegram as inline status messages. Only one task is in_progress at a time — sequential, focused execution.

```
Progress: 2/4 tasks completed (50%)
[✓] Fetch PR diff from GitHub
[✓] Analyze security implications
[→] Draft review comments
[ ] Post review to GitHub
```
| Self-governing threshold The TodoWriteTool description instructs the LLM to use it when a task requires 3 or more distinct steps. Simpler tasks proceed without creating a list — no overhead for quick queries. |
| --- |

### 5.5.5 Shell Execution Security Model

Herald wraps ShellTools from spring-ai-agent-utils with a three-layer security model. The library's ShellTools handles execution; Herald adds the guardrails around it.

| Layer | What It Does | Implementation |
| --- | --- | --- |
| Layer 1 — Blocklist (Phase 1) | shell_exec rejects commands matching destructive patterns before execution. Patterns: rm -rf /, mkfs, dd if=, sudo rm, curl | sh, chmod -R 777, and similar. Commands with sudo, >, or pipe to shell require confirmation prompt in Telegram. | HeraldShellDecorator wraps ShellTools at call time. |
| Layer 2 — Dedicated Mac user (Phase 3) | Herald runs as a dedicated Mac user (herald) with a scoped home (~/.herald-workspace/). SSH keys, dotfiles not visible. | launchd plist runs as herald user. Created via System Preferences. |
| Layer 3 — Docker sandbox for code execution (Phase 6) | Subagent code execution runs in Docker container. Mounts ~/herald-workspace/ read-write and ~/.herald/ read-only. Container destroyed after each run. | docker run --rm -v. Only applies to sandboxed code_exec, not normal shell_exec. |

### 5.6 MCP Integration

#### 5.6.1 Overview

Herald connects to MCP servers over HTTP/SSE (the standard MCP transport). MCP servers expose tools that are registered alongside the built-in tools. From the model's perspective, MCP tools are indistinguishable from native tools.

#### 5.6.2 Day-1 MCP Servers

| MCP Server | Capabilities Exposed |
| --- | --- |
| Google Calendar MCP | List events, create events, find free time, respond to invites |
| Gmail MCP | Search messages, read threads, create drafts, list labels |

#### 5.6.3 MCP Configuration

```
mcp_servers:
- name: google-calendar
url: https://gcal.mcp.example.com/mcp
auth: oauth2
- name: gmail
url: https://gmail.mcp.example.com/mcp
auth: oauth2
```
MCP tools are fetched on startup and refreshed every 5 minutes. If an MCP server is unreachable, its tools are removed from the active set and the model is notified via system prompt.

### 5.7 Proactive Heartbeats & Cron

#### 5.7.1 Overview

Herald doesn't wait to be asked. Cron jobs are scheduled agent runs that can send proactive Telegram messages. The model uses the telegram_send tool to push messages to you.

#### 5.7.2 Built-in Schedules

| Job | Behavior |
| --- | --- |
| Morning Briefing | 7:00 AM weekdays — weather, calendar events today, top 3 priorities from memory, any flagged emails |
| Weekly Review | 6:00 PM Friday — recap of week's activity, open items, next week preview |

#### 5.7.3 Dynamic Cron Management

The model can create, modify, and delete cron jobs based on conversation. Examples:

- "Remind me to stand up every hour between 9am and 5pm"
- "Check my GitHub notifications every morning at 8"
- "Alert me if it's going to rain today before I leave for a hike"
#### 5.7.4 Cron Telegram Commands

- /cron list — show all registered cron jobs with their schedules
- /cron disable <name> — pause a job without deleting it
- /cron enable <name> — re-enable a paused job

## 6. Configuration

### 6.1 Config File (herald.yaml)

Single config file at ~/.herald/herald.yaml. All secrets via environment variables. Subagent definitions live in .claude/agents/ — not in YAML. Routing is handled by TaskTool, not configured here.

```
telegram:
allowed_chat_id: 123456789
bot_token: ${TELEGRAM_BOT_TOKEN}
```
```
# ── Model Providers ─────────────────────────────
providers:
anthropic:
api_key: ${ANTHROPIC_API_KEY}
openai:
api_key: ${OPENAI_API_KEY}
base_url: https://api.openai.com
ollama:
base_url: http://localhost:11434
api_key: ollama
```
```
# ── Main Agent ───────────────────────────────────
agent:
provider: anthropic
model: claude-sonnet-4-5
max_tokens: 8192
persona: |
Your name is Herald. You are Dan's personal AI agent — autonomous,
capable, dry wit. Dan is Head of Technical Marketing at Broadcom/VMware
(Tanzu). Direct and technical. Occasionally reference your namesake.
You have access to specialist subagents for deep research. Use them.
```
```
# ── Subagents (see also .claude/agents/*.md) ────
subagents:
dir: .claude/agents
models:
haiku: claude-haiku-4-5
sonnet: claude-sonnet-4-5
opus: claude-opus-4-5
```
```
# ── Skills ───────────────────────────────────────
skills:
dir: .claude/skills           # Compatible with Claude Code skills
watch: true
```
```
memory:
db_path: ~/.herald/herald.db
```
```
cron:
timezone: America/New_York
morning_briefing:
enabled: true
schedule: '0 7 * * 1-5'
```
```
mcp_servers:
- name: google-calendar
url: ${GCAL_MCP_URL}
- name: gmail
url: ${GMAIL_MCP_URL}
```
```
ui:
enabled: true
port: 8080
host: 0.0.0.0
```
### 6.2 Runtime Model Switching

Agent models can be switched at runtime via Telegram without restarting the service:

- /model alpha ollama qwen2.5-coder — switch alpha to local Ollama (zero cost)
- /model alpha anthropic claude-sonnet-4-5 — switch alpha back to Sonnet
- /model omega anthropic claude-opus-4-5 — omega on Opus
- /model status — show current provider + model for both agents
Runtime changes persist in a model_overrides table and survive restarts. Config file = boot defaults; runtime overrides take precedence.

### 6.3 Environment Variables

| Variable | Description |
| --- | --- |
| TELEGRAM_BOT_TOKEN | Bot token from @BotFather |
| ANTHROPIC_API_KEY | Anthropic API key |
| OPENAI_API_KEY | OpenAI API key |
| GCAL_MCP_URL | Google Calendar MCP server URL |
| GMAIL_MCP_URL | Gmail MCP server URL |
| HERALD_CONFIG | Override config path (default: ~/.herald/herald.yaml) |

## 7. Slash Commands Reference

These commands are handled directly by Herald without going through the agent loop. They are operational controls, not conversation.

| Command | Description |
| --- | --- |
| /help | Show all available commands with descriptions |
| /memory list | Display all stored memory entries |
| /memory clear | Clear all memory (prompts for confirmation) |
| /memory set <key> <value> | Manually set a memory entry |
| /skills list | Show all loaded skills with descriptions |
| /skills reload | Force reload skills from disk |
| /cron list | List all cron jobs with schedules and status |
| /cron disable <name> | Pause a cron job |
| /cron enable <name> | Re-enable a paused cron job |
| /status | Show system status: uptime, model, MCP connections, last cron run |
| /debug | Show current context size, memory entries count, active tools count |
| /reset | Clear conversation history (not memory) and start fresh |

## 8. Identity & System Prompt

### 8.1 Base System Prompt

The base system prompt establishes the assistant's identity, capabilities, and behavioral rules. It is not user-editable but is extended by agent.system_prompt_extra in the config.

| Base Prompt Sections Identity & role  ·  Current datetime & timezone  ·  Available tools reference  ·  Memory management rules (what to store automatically)  ·  Communication style rules  ·  Safety rules (what to refuse) |
| --- |

### 8.2 Communication Style

- Direct and technical — no filler phrases, no excessive caveats
- Assume high technical competence (25+ years engineering background)
- Use markdown formatting for code and structured data
- Ask clarifying questions when genuinely ambiguous, not as a hedge
- Proactively surface relevant context from memory without being asked

## 9. Technical Implementation

### 9.1 Technology Stack

| Component | Technology |
| --- | --- |
| Language | Java 21 (virtual threads enabled — spring.threads.virtual.enabled=true) |
| Framework | Spring Boot 4.0.x |
| AI Framework | Spring AI 2.0.0-M2 (spring-ai-bom) |
| Agent Utils | org.springaicommunity:spring-ai-agent-utils 0.4.2+ (SkillsTool, ShellTools, FileSystemTools, TodoWriteTool, AskUserQuestionTool, TaskTool) |
| Telegram | pengrad/java-telegram-bot-api (long polling) |
| Anthropic | spring-ai-starter-model-anthropic — main agent (Sonnet) + research subagent (Opus) |
| OpenAI + Ollama | spring-ai-starter-model-openai — shared starter, base-url configures target |
| Agent abstraction | Single ChatClient bean — TaskTool handles subagent delegation; ToolCallAdvisor handles loop |
| Chat Memory | spring-ai-starter-chat-memory-repository-jdbc + SQLite dialect |
| MCP Client | spring-ai-starter-mcp-client (non-web autoconfigure — fixed in 2.0.0-M2) |
| Database | SQLite via org.xerial/sqlite-jdbc + Spring Data JDBC |
| Cron | Spring @Scheduled + ThreadPoolTaskScheduler |
| Observability | Micrometer + Spring Actuator (structured logs, metrics — zero config) |
| Config | Spring Boot application.yaml + environment variable override |
| File Watching | Java WatchService (skills hot-reload) |
| Process Management | macOS launchd (two plists: com.herald.plist + com.herald-ui.plist) |

### 9.2 Project Structure

```
herald/                                      # Maven multi-module project
├── pom.xml                                  # Parent POM — Spring AI BOM 2.0.0-M2
├── herald-bot/
│   └── src/main/java/com/herald/
│       ├── HeraldApplication.java           # @SpringBootApplication entry point
│       ├── agent/
│       │   └── HeraldAgentConfig.java       # Single ChatClient bean with all tools
│       ├── telegram/
│       │   ├── TelegramPoller.java          # Long-polling loop (@Scheduled)
│       │   ├── CommandHandler.java          # Slash command dispatch
│       │   ├── TelegramQuestionHandler.java # AskUserQuestionTool bridge
│       │   └── MessageFormatter.java        # Splitting, formatting
│       ├── memory/
│       │   └── MemoryTools.java             # @Tool memory_set/get/list
│       ├── tools/
│       │   ├── HeraldShellDecorator.java    # Wraps library ShellTools + blocklist
│       │   └── WebTools.java                # @Tool web_fetch, web_search
│       ├── cron/
│       │   ├── CronService.java             # Dynamic @Scheduled job management
│       │   └── BriefingJob.java             # Morning briefing assembly
│       └── config/
│           └── HeraldConfig.java            # @ConfigurationProperties
│   └── src/main/resources/
│       ├── application.yaml
│       └── prompts/
│           └── MAIN_AGENT_SYSTEM_PROMPT.md  # Based on MAIN_AGENT_SYSTEM_PROMPT_V2
├── herald-ui/                               # Module: Console
│   ├── src/main/java/com/herald/ui/
│   │   ├── api/                             # @RestController endpoints
│   │   └── sse/                             # SseEmitter status stream
│   └── frontend/                            # Vue 3 + Vite
│       └── src/
│           ├── App.vue
│           ├── router.ts
│           └── pages/
│               ├── SkillsEditor.vue
│               ├── SystemStatus.vue
│               ├── MemoryViewer.vue
│               ├── CronBuilder.vue
│               └── ConversationHistory.vue
├── .claude/
│   ├── agents/                              # Subagent definitions (*.md)
│   │   ├── research.md                     # Research Agent — Opus, web tools
│   │   ├── explore.md                      # Fast read-only codebase exploration
│   │   └── plan.md                         # Software architect / planning
│   └── skills/                              # Skills (Claude Code compatible)
│       ├── github/SKILL.md
│       ├── weather/SKILL.md
│       └── broadcom/SKILL.md
├── herald.yaml.example
├── com.herald.plist                         # launchd: bot
├── com.herald-ui.plist                      # launchd: console
├── Makefile
└── README.md
```
### 9.3 macOS launchd Service

Herald runs as a macOS launchd user agent — starts on login, restarts on crash, logs to ~/Library/Logs/herald.log. Both modules produce executable fat JARs via spring-boot-maven-plugin.

```
# Build fat JARs
mvn -pl herald-bot package -DskipTests
```
```
# Install as launchd service
cp com.herald.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.herald.plist
```
```
# Uninstall
launchctl unload ~/Library/LaunchAgents/com.herald.plist
```

## 10. Web UI — Herald Console

The Herald Console is a separate service (herald-ui) running on the same Mac. It binds to the local network (0.0.0.0:8080) so it's accessible from any device on your network — phone, tablet, other laptop — without tunnels or external infrastructure. Telegram is for conversation. The Console is for management.

| Design Principle Anything awkward to do in a chat message belongs in the Console. Editing a multi-line SKILL.md, building a cron schedule, reviewing conversation history with tool call details — these need a real interface. |
| --- |

### 10.1 Architecture

Two independent Spring Boot modules share one SQLite database. The bot (herald-bot) has write access. The Console (herald-ui) has read access plus a commands table for triggering bot actions. No direct process-to-process communication — SQLite is the bus.

| Component | Role | Owns |
| --- | --- | --- |
| herald-bot | Core bot process | Telegram polling, agent loop, tool dispatch, cron |
| herald-ui | Console backend | Spring MVC REST API, static Vue assets, skills file management |
| herald.db | Shared SQLite | Memory, sessions, cron jobs, commands queue |
| ~/.herald/skills/ | Shared filesystem | Both modules read; UI writes; bot watches via WatchService |

#### 10.1.1 IPC via Commands Table

A commands table in SQLite lets the Console trigger bot actions asynchronously. The bot polls every 2 seconds and executes pending commands.

```
CREATE TABLE commands (
id           INTEGER PRIMARY KEY,
type         TEXT NOT NULL,  -- 'reload_skills' | 'send_message' | 'run_cron'
payload      TEXT,           -- JSON
status       TEXT DEFAULT 'pending',
created_at   DATETIME,
completed_at DATETIME
);
```
### 10.2 Tech Stack

| Concern | Choice |
| --- | --- |
| UI Framework | Vue 3 (Composition API) + Vite |
| Styling | Tailwind CSS |
| Code Editor | CodeMirror 6 (Markdown + YAML modes) |
| State Management | Pinia |
| HTTP Client | Fetch API |
| Backend | Spring MVC @RestController (herald-ui module) |
| Live Updates | Spring MVC SseEmitter — status stream |
| Build Output | Vite builds to herald-ui/src/main/resources/static/, served by Spring Boot |

### 10.3 Pages & Features — Priority Order

#### 10.3.1 Skills Editor — Priority 1

The primary reason the Console exists. Editing SKILL.md files in a Telegram message is not viable for anything non-trivial.

- Left panel: file tree of ~/.herald/skills/ — all skill folders, each showing name and description from frontmatter
- Right panel: CodeMirror 6 editor with Markdown syntax highlighting and YAML frontmatter awareness
- Toolbar: Save, Discard, Delete Skill, New Skill
- New Skill flow: enter name → creates ~/.herald/skills/<name>/SKILL.md with template frontmatter → opens in editor
- Save writes file to disk → bot detects via fsnotify → reloads within ~250ms
- Live status chip: 'Loaded' / 'Reloading' / 'Error' updated via SSE from the bot's reload events in SQLite
- Bundled skills shown read-only with a lock icon
| Key UX Detail A 'Last loaded by bot' timestamp updates via SSE. When you save, you see it tick forward within a second — confirming the bot picked up the change without leaving the editor. |
| --- |

#### 10.3.2 System Status — Priority 2

Live dashboard showing health of all Herald components. Driven by SSE — no page refresh needed.

- Bot: running / stopped, PID, uptime, restart count
- Model: name, requests today, estimated token spend today
- MCP connections: each server with status, last ping time, tool count
- Skills: total loaded, last reload timestamp, any parse errors
- Memory: entry count, database file size
- Cron: next scheduled run per job, last run time, last run result
- Recent activity feed: last 20 agent turns — timestamp, message preview, tool calls made
#### 10.3.3 Memory Viewer/Editor — Priority 3

Table view of all key-value memory pairs. Most useful for correcting or pre-seeding memory without going through Telegram.

- Columns: Key, Value, Last Updated
- Inline editing: click any value to edit in-place
- Add entry: empty row at top
- Delete: trash icon per row with single confirmation
- Filter by key name
- Export as JSON / Import from JSON blob
#### 10.3.4 Cron Job Builder — Priority 4

Visual management of all scheduled jobs — built-in and model-created.

- List: name, human-readable schedule, raw cron expression, status, last run, next run
- Enable/disable toggle per job
- Edit panel: name, visual cron builder (selectors that emit a valid cron string), prompt text
- New job button with same edit panel
- Run Now: triggers job immediately via commands table
- Last run log: expandable row with full execution output
- Built-in jobs (morning briefing, weekly review) are editable but not deletable
#### 10.3.5 Conversation History — Priority 5

Read-only log of all conversations. Primarily for debugging — when you want to see exactly what tools were called and what they returned.

- Paginated, newest first, 50 messages per page
- Each turn shows: role, timestamp, content, tool calls (expandable with inputs + outputs)
- Filter by date range, search by content (SQLite FTS)
- Clear history (with confirmation) — does not affect memory
### 10.4 REST API Surface

herald-ui exposes a REST API at http://<hostname>:8080/api. All endpoints unauthenticated — network trust model.

| Endpoint | Purpose | Notes |
| --- | --- | --- |
| GET /api/status | Live status snapshot + SSE stream at /api/status/stream | Bot health, MCP, cron, model usage |
| GET /api/skills | List all skills with metadata | Source (bundled vs local), load status |
| GET /api/skills/:name | Get raw SKILL.md content | Plain text |
| PUT /api/skills/:name | Save SKILL.md | Writes file; bot picks up via fsnotify |
| POST /api/skills | Create new skill | Folder + template SKILL.md |
| DELETE /api/skills/:name | Delete skill | Refuses bundled skills |
| GET /api/memory | List all memory entries | {key, value, updated_at}[] |
| PUT /api/memory/:key | Set memory entry | Upserts |
| DELETE /api/memory/:key | Delete memory entry |  |
| GET /api/cron | List cron jobs | Includes last_run, next_run |
| PUT /api/cron/:id | Update cron job | Schedule, prompt, enabled |
| POST /api/cron/:id/run | Trigger job now | Via commands table |
| GET /api/messages | Conversation history | ?page=&limit=&search= |
| DELETE /api/messages | Clear history | Preserves memory |
| POST /api/commands | Send IPC command to bot | Generic command dispatch |

### 10.5 Configuration

```
ui:
enabled: true
port: 8080
host: 0.0.0.0      # Binds all interfaces for LAN access
db_path: ~/.herald/herald.db
skills_dir: ~/.herald/skills
```
### 10.6 Project Structure (herald-ui)

```
herald-ui/
├── main.go
├── api/
│   ├── skills.go
│   ├── memory.go
│   ├── cron.go
│   ├── messages.go
│   ├── status.go        # SSE stream
│   └── commands.go
└── frontend/            # Vue 3 + Vite
└── src/
├── App.vue
├── router.ts
├── stores/
└── pages/
├── SkillsEditor.vue
├── SystemStatus.vue
├── MemoryViewer.vue
├── CronBuilder.vue
└── ConversationHistory.vue
```

## 11. Build Phases

### Phase 1 — Core Loop + Spring AI Foundation (Week 1)

Phase 1 builds the working single-agent Telegram bot with tools from spring-ai-agent-utils. The goal is a functional loop: Telegram message → Anthropic Claude → tool execution → response → persisted history.

- Maven multi-module project: parent POM with Spring AI BOM 2.0.0-M2, Spring Boot 4.0.x, spring-ai-agent-utils 0.4.2
- Single main ChatClient with ToolCallAdvisor + MessageChatMemoryAdvisor
- Telegram long-polling via pengrad, single-user auth (allowed_chat_id)
- Add spring-ai-agent-utils dependency; wire ShellTools and FileSystemTools as the primary file/shell tools
- HeraldShellDecorator wraps ShellTools with the destructive pattern blocklist + Telegram confirmation prompt for flagged commands
- TodoWriteTool registered from library — LLM self-governs when to use it
- AskUserQuestionTool + TelegramQuestionHandler — LLM asks clarifying questions via Telegram before acting
- SQLite via JdbcChatMemoryRepository — messages, memory tables (WAL mode enabled)
- MemoryTools (@Tool beans: memory_set/get/list/delete)
- System prompt modeled on MAIN_AGENT_SYSTEM_PROMPT_V2 from spring-ai-agent-utils
- /help, /status, /reset commands
- Structured trace logging via Micrometer (turn_id, model, tokens, latency_ms)
- launchd plist for macOS service; fat JAR via spring-boot-maven-plugin
| Phase 1 Exit Criteria Telegram → main agent (Anthropic/Sonnet) → shell command executes → fact persisted across restarts. Destructive commands blocked or require confirmation. TodoWriteTool fires on multi-step tasks. AskUserQuestionTool asks Dan clarifying questions via Telegram. Trace log entry written per turn. |
| --- |

### Phase 2 — Subagents + Multi-Provider (Week 2)

Phase 2 adds the TaskTool-based subagent system and multi-provider support. No AgentRouter — subagent delegation is description-driven by the model.

- Add TaskTool / TaskToolCallbackProvider from spring-ai-agent-utils
- Create .claude/agents/research.md — Research Agent persona, Opus model, web tools, deep research instructions
- Create .claude/agents/explore.md and .claude/agents/plan.md (built-in subagent definitions)
- Multi-model routing: configure named ChatClient builders (haiku / sonnet / opus) in HeraldAgentConfig
- OpenAI-compatible ChatModel bean: base-url=localhost:11434 for Ollama
- model_usage table + MeterRegistry cost tracking per turn (subagent turns tracked separately)
- /model status, /model <provider> <model> commands (main agent model at runtime)
- Background task support: TaskOutputTool for async subagent results
| Phase 2 Exit Criteria 'Research the top 5 MCP frameworks' delegates to research subagent on Opus. Subagent runs in isolated context, returns findings. Main agent synthesizes and replies. /model switches main agent to Ollama at runtime. Cost logged per turn including subagent turns. |
| --- |

### Phase 3 — Skills & Memory (Week 3)

Phase 3 wires SkillsTool for progressive skill loading, adds CONTEXT.md, and tightens memory and context management.

- Add SkillsTool from spring-ai-agent-utils — replaces any hand-rolled skill injection
- Create .claude/skills/ directory structure with starter skills (github, weather, broadcom)
- Java WatchService hot-reload: new/updated .claude/skills/ content live within 250ms
- /skills list and /skills reload commands
- Auto-memory: model proactively stores facts via memory_set tool
- CONTEXT.md injection — human-editable standing brief about Dan injected before memory on every turn
- Context compaction: MessageWindowChatMemory window + 80% token ceiling backstop
- conversationHistoryEnabled(false) on ToolCallAdvisor — memory managed entirely by MessageChatMemoryAdvisor
- Security Layer 2: dedicated herald Mac user + scoped workspace (~/.herald-workspace/); launchd plist updated
| Phase 3 Exit Criteria Drop skill folder in .claude/skills/, bot hot-reloads within 250ms. Skill invoked on demand — not injected into every turn. Model stores and retrieves facts across sessions. CONTEXT.md changes reflected immediately. |
| --- |

### Phase 4 — Proactive & MCP (Week 4)

Phase 4 adds scheduling, external integrations via MCP, and web research tools.

- Dynamic cron management: ThreadPoolTaskScheduler + ScheduledFuture registry; /cron commands
- Morning briefing job: wttr.in weather + Google Calendar + priorities + adaptive 'things you'd want to know' section
- Proactive message batching — configurable digest window for non-urgent cron notifications
- spring-ai-starter-mcp-client autoconfiguration: Google Calendar + Gmail MCP servers
- WebTools: web_fetch and web_search as @Tool beans
- Research subagent multi-step research: sequential web fetch depth configurable in research.md
| Phase 4 Exit Criteria 7am briefing arrives unprompted. 'Research top 5 MCP frameworks' subagent uses web tools for multi-source deep report. Meeting scheduled via Telegram appears in Google Calendar. |
| --- |

### Phase 5 — Herald Console (Week 5)

Phase 5 builds the herald-ui module — a Spring Boot + Vue 3 management interface for Herald.

- herald-ui Spring Boot module: @RestController endpoints + SseEmitter status stream
- Vite builds Vue 3 assets to src/main/resources/static/ — served by Spring Boot
- Skills editor: CodeMirror 6, file tree for .claude/skills/ and .claude/agents/, live hot-reload status
- System status: SSE-driven live dashboard, Micrometer metrics, MCP server health
- Memory viewer/editor: inline edit, JSON import/export
- Cron builder: visual schedule builder, run now, last run log
- Conversation history: paginated, searchable, tool call details, subagent call trees
- Model switcher panel (mirrors /model commands via commands table IPC)
- com.herald-ui.plist launchd service
| Phase 5 Exit Criteria http://herald.local:8080 accessible from phone. Edit SKILL.md in browser, bot reloads within 1 second. View subagent call details in conversation history. |
| --- |

### Phase 6 — Polish (Week 6)

- Voice message transcription (Whisper API via OpenAI-compatible endpoint)
- Image/photo support (vision input to main agent via Spring AI multimodal content)
- Telegram file/document handling to ~/.herald/inbox/
- Security Layer 3: Docker sandbox for subagent code execution — docker run --rm, mounts ~/herald-workspace/ read-write, container destroyed after each run
- ntfy push notification tool (@Tool ntfy_send — single HTTP POST, zero infrastructure)
- Improved typing indicators and ephemeral status messages
- Spring AI 2.0.0-M2 → latest milestone upgrade (budget 2-3 hours)
- Bundled starter skills expanded (GitHub, weather, Broadcom/Tanzu knowledge base)
- Starter subagent definitions (code-reviewer.md, calendar-planner.md)
- Console: dark mode, mobile-responsive layout
- README, setup docs, herald.yaml.example

## 12. Open Questions

| Question | Discussion |
| --- | --- |
| Spring AI 2.0.0-M2 milestone stability | M2 is pre-GA. Spring team targets M3 roughly 6-8 weeks out. Expect one import path rename or autoconfiguration tweak per milestone. Budget 2-3 hours per upgrade, not a day. Do not ship to others on M2. |
| MCP OAuth token handling | Claude.ai MCP servers use OAuth 2.1. Spring AI MCP client handles the transport but token refresh may need a stored token file + refresh wrapper. Prototype against Google Calendar first — it's the highest-value MCP server. |
| Shell exec security — blocklist completeness | The Phase 1 blocklist catches obvious destructive patterns. After a week of real use, review the shell_exec log for near-misses and expand the list. Consider an allowlist mode (only permitted commands) as an optional stricter config for sensitive periods. |
| Dynamic tool discovery index freshness | tool-search-tool with Lucene builds an index at startup. When MCP tools change (server restart, new server added), index must be rebuilt. Decide: rebuild on @RefreshScope trigger vs. timed rebuild vs. manual /tools reload command. |
| CONTEXT.md schema | Human-editable structured doc about Dan injected every turn. Define the schema: free-form markdown vs. structured YAML front matter vs. heading-delimited sections. Structured makes targeted updates easier; free-form is lower friction. |
| Virtual threads + SQLite | Spring Boot 4 with virtual threads enabled and SQLite JDBC may surface connection contention under concurrent cron + Telegram polling. Test under load in Phase 1. org.xerial/sqlite-jdbc supports WAL mode — enable it. |
| Auto-routing threshold tuning | 0.7 is the starting guess. Too high = omega never auto-invoked. Too low = expensive Opus calls for simple questions. Expose score in /debug. Tune after one week of real use using feedback table data. |
| Ollama model selection | qwen2.5-coder:7b for code tasks. For general tasks (calendar, email, quick Q&A) a smaller general model like llama3.2:3b may be faster and good enough. Benchmark both in Phase 2. |

## 13. Success Metrics

This is a personal tool, so success is qualitative. The bar is:

- You reach for the alpha agent before Google for questions the bot can actually answer
- The morning briefing is genuinely useful at least 4 out of 5 weekdays
- You've created at least 3 custom skills within the first month
- The bot correctly recalls facts about you without being re-told
- Zero manual restarts required in the first 30 days of operation

## Appendix: Herald vs. OpenClaw

For reference — what we kept, what we cut, and why.

| Feature | Decision | Rationale |
| --- | --- | --- |
| Telegram messaging | ✅ Kept | Core value — messaging app you already use |
| Persistent memory | ✅ Kept | The whole point of a personal assistant |
| Skills system | ✅ Kept (simplified) | Files in a folder, no marketplace |
| Shell / file access | ✅ Kept | The assistant needs hands |
| Proactive cron / heartbeats | ✅ Kept | Reaching out, not just responding |
| MCP integration | ✅ Kept | Calendar and email are high-value |
| Web browsing | ✅ Kept | web_fetch + web_search via tools |
| Multi-agent routing | ❌ Cut | Single user, single agent |
| 15+ channel adapters | ❌ Cut | Telegram only |
| macOS companion app | ❌ Cut | Telegram IS the UI |
| CLI management layer | ❌ Cut | Bot manages itself via /commands |
| ClawdHub marketplace | ❌ Cut | Skills are just local files |
| Formal security verification | ❌ Cut | Personal tool, you trust yourself |
| Multi-gateway support | ❌ Cut | Single Go binary, single Mac |
| Multi-agent routing (OpenClaw) | ✅ Added (simplified) | Two agents (primary/research), keyword + LLM router, shared history; personas fully configurable via system prompt |
| Multi-provider model support | ✅ Added (new) | Anthropic + OpenAI + Ollama via ModelProvider interface |
| Runtime model switching | ✅ Added (new) | /model command switches provider/model without restart |
| Web UI / Herald Console | ✅ Added (new) | Management interface — skills editor, status, memory, cron |
| Node.js runtime | ❌ Cut (→ Java/Spring Boot) | Fat JAR, Spring ecosystem, no external runtime beyond JVM |
| Go runtime (earlier pivot) | ❌ Cut (→ Java/Spring Boot) | Spring AI 2.0.0-M2 covers provider abstraction, tool loop, memory, skills — dev velocity beats binary size for a personal tool |
