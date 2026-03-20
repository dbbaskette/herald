# Herald — Status Report & Next Steps

**Date:** March 18, 2026
**Version:** 0.4.1-SNAPSHOT
**Stack:** Java 21 / Spring Boot 4.0 / Spring AI 2.0.0-SNAPSHOT

---

## Executive Summary

Herald is a fully operational personal AI agent with Telegram integration, persistent memory, multi-model support, subagent delegation, proactive scheduling, and a Vue 3 management console. It implements 4 of the 5 patterns from the Spring AI Agentic Patterns blog series, with the 5th (A2A Protocol) planned. Beyond the blog series, Herald adds 7 significant extensions of its own.

---

## What's Built (Current State)

### Core Agent Loop — Complete
- `ChatClient`-based agent with a 5-layer advisor chain
- SQLite-backed conversation history (`JsonChatMemoryRepository` storing full JSON blobs)
- `OneShotMemoryAdvisor` fixes Spring AI's duplicate-message bug
- `ContextCompactionAdvisor` as backstop against context overflow
- `ToolPairSanitizingAdvisor` filters malformed tool call/response pairs
- `PromptDumpAdvisor` for token usage diagnostics

### Blog Pattern Coverage

| Pattern | Blog Post | Status | Notes |
|---------|-----------|--------|-------|
| **Skills** | Part 1 | ✅ 4/7 features | Format, discovery, matching, execution all working. Hot-reload added as Herald extension. |
| **AskUserQuestion** | Part 2 | ✅ 3/4 features | Core pattern, Telegram handler, async bridge all complete. |
| **TodoWrite** | Part 3 | ✅ 4/5 features | Decomposition, lifecycle, progress events, memory integration all working. |
| **Subagents** | Part 4 | ✅ 5/6 features | TaskTool, agent registry, context isolation, multi-model routing, built-in + custom agents. |
| **A2A Protocol** | Part 5 | ⏳ 0/4 features | Not started. |

### Herald-Specific Extensions (Beyond Blog)

| Extension | Status | Description |
|-----------|--------|-------------|
| Advisor Chain | ✅ | 5-layer chain: DateTime → ContextMd → MemoryBlock → Compaction → OneShotMemory |
| Persistent Memory | ✅ | SQLite key/value hot memory + Obsidian cold storage + archival jobs |
| Proactive Scheduling | ✅ | CronService with morning briefings, DB-persisted jobs, `/cron` commands |
| Google Workspace | ✅ | Gmail + Calendar via `gws` CLI (`GwsTools`) |
| Multi-Model Switching | ✅ | Runtime switch across Anthropic/OpenAI/Ollama/Gemini, persisted to DB |
| Management Console | ✅ | Vue 3 SPA: chat, memory, cron, skills, status, settings, conversations |
| Shell Security | ✅ | Regex blocklist, timeouts, confirmation gates via `HeraldShellDecorator` |

### Tool Inventory (20+ tools registered)

**From spring-ai-agent-utils:** SkillsTool, TaskTool, TaskOutputTool, AskUserQuestionTool, TodoWriteTool
**Herald-built:** MemoryTools (5 ops), GwsTools (Gmail + Calendar), WebTools (fetch + search), FileSystemTools, HeraldShellDecorator, TelegramSendTool, CronTools

### Test Coverage
- **herald-bot:** 22+ test files covering agents, advisors, tools, cron, memory, telegram, config
- **herald-ui:** 6+ controller tests + frontend spec files
- **Frontend:** Store and page-level `.spec.ts` tests

---

## Gaps & Planned Work

### Priority 1 — A2A Protocol (Blog Part 5)

The only unimplemented blog pattern. Four features needed:

| Feature | Effort | Value |
|---------|--------|-------|
| **A2A Server** — expose Herald as `/.well-known/agent-card.json` | Medium | Enables other agents to discover and call Herald |
| **A2A Client** — discover and call remote agents | Medium | Extends delegation beyond in-process subagents |
| **AgentCard** — define Herald's capabilities in A2A format | Small | Required for server exposure |
| **LLM-driven routing** — semantic selection of remote agents | Small | Falls out naturally once client is wired |

**Dependency:** `spring-ai-a2a-server-autoconfigure` and `spring-ai-a2a-client-autoconfigure` starters. These provide most of the plumbing — Herald needs to configure the `AgentCard` and wire the `DefaultAgentExecutor`.

**Recommendation:** Start with A2A Server (expose Herald), then add Client when there's a second agent to talk to.

---

### Priority 2 — Incomplete Blog Features

| Feature | Blog Part | Gap | Effort |
|---------|-----------|-----|--------|
| **Parallel/background subagents** | Part 4 | TaskTool supports it but Herald hasn't exercised concurrent execution | Small — test and document existing capability |
| **Classpath skill packaging** | Part 1 | Skills load from filesystem only, no JAR/classpath support | Small — use `SkillsTool.addSkillsResource()` |
| **Anthropic Native Skills API** | Part 1 | Cloud-sandboxed document generation not wired | Medium — depends on Anthropic SDK support |
| **MCP Elicitation** | Part 2 | Server-driven question flow not implemented | Medium — `@McpElicitation` integration |
| **Blog system prompt template** | Part 3 | Herald uses custom prompt vs. `MAIN_AGENT_SYSTEM_PROMPT_V2` | Low priority — Herald's prompt is tuned for personal assistant use |

---

### Priority 3 — Technical Debt & Hardening

| Item | Area | Notes |
|------|------|-------|
| **Streaming responses** | Agent → Telegram | Currently waits for full response before sending. Streaming would improve perceived latency for long responses. |
| **Web console chat streaming** | herald-ui | `ChatProxyController` does synchronous proxy with 5-min timeout. SSE/WebSocket streaming would match the Telegram experience. |
| **Docker/container sandbox** | Shell security | `HeraldShellDecorator` is regex-based. A container sandbox would be more robust for untrusted commands. |
| **Conversation threading** | Telegram | Single conversation ID per chat. No support for parallel threads or topic-based isolation. |
| **Error recovery** | CronService | Cron jobs that fail don't have retry/backoff logic. `lastRunResult` tracks status but no automated recovery. |
| **Token usage tracking** | AgentMetrics | Metrics are collected in-memory but not persisted to `model_usage` table consistently. |

---

### Priority 4 — Feature Enhancements (Phase 6 Roadmap)

| Feature | Description |
|---------|-------------|
| **Voice messages** | Telegram voice note → transcription → agent → TTS reply |
| **Image/vision** | Process images sent via Telegram using multimodal models |
| **Dark mode** | Herald Console theme toggle |
| **Notification preferences** | Per-cron-job notification channels and quiet hours |
| **Skill marketplace** | Browse/install community skills from a registry |
| **Multi-user** | Support multiple Telegram users with separate contexts (currently single-user) |

---

## Architecture Health

### Strengths
- **Clean separation of concerns:** Advisors handle cross-cutting concerns, tools are focused, config is externalized
- **spring-ai-agent-utils alignment:** Using upstream tools means Herald benefits from library improvements automatically
- **Extensibility:** Skills system allows adding capabilities without code changes
- **Observability:** AgentMetrics, PromptDumpAdvisor, and structured logging provide good debugging tools
- **Test coverage:** Both backend modules have meaningful test suites

### Risks
- **Spring AI 2.0.0-SNAPSHOT dependency:** Pre-release APIs may change. The `OneShotMemoryAdvisor` and `ToolPairSanitizingAdvisor` exist specifically to work around Spring AI bugs — these may need updating when Spring AI 2.0 GA ships.
- **SQLite single-writer:** WAL mode helps, but concurrent writes from bot + UI + cron could cause contention under load.
- **gws CLI dependency:** Gmail/Calendar integration depends on an external CLI tool (`gws`). If it breaks or changes, `GwsTools` breaks silently.
- **Model default staleness:** Default model names (`claude-sonnet-4-5`, `claude-opus-4-5`) will need updating as model versions advance.

---

## Recommended Next Sprint

1. **A2A Server exposure** — Wire `spring-ai-a2a-server-autoconfigure`, define Herald's `AgentCard`, expose at `/.well-known/agent-card.json`. This completes blog pattern coverage and makes Herald discoverable.

2. **Parallel subagent execution** — Test and validate that TaskTool can run multiple subagents concurrently (e.g., research + explore in parallel). Document the pattern and add a test.

3. **Streaming responses** — Add SSE streaming from `AgentService` through to both Telegram (chunked sends) and the web console (EventSource). This is the single biggest UX improvement available.

4. **Persist AgentMetrics** — Wire `AgentMetrics` data into the `model_usage` SQLite table so token spend and latency are queryable across sessions.

---

*Generated from codebase analysis at commit `63f81e4` on branch `main`*
