# Herald — Spring AI Agentic Patterns: Feature Comparison

> How Herald adopts (and adapts) the five patterns from the spring-ai-agent-utils blog series

**dbbaskette/herald  •  March 2026**

---

**Legend:**
- ✅ **Implemented** — exact match to the blog pattern
- 〜 **Partial / Custom Variant** — adopted but with meaningful differences
- ⏳ **Planned** — on the roadmap, not yet done
- ↗ **Intentional Divergence** — Herald went a different direction by design

---

## Part 1 — Agent Skills (Modular, Reusable Capabilities)

> Skills are Markdown files with YAML frontmatter that give an agent domain knowledge on demand, using progressive disclosure: only names/descriptions are embedded at startup; full instructions load when matched.

### Skill Format

**Blog:** `SKILL.md` with YAML frontmatter (`name` + `description` required). Folder structure: `my-skill/SKILL.md`, `scripts/`, `references/`, `assets/`. Frontmatter supports simple strings and complex YAML structures.

**Herald:** ✅ **Implemented.** Herald uses the same `SKILL.md` + YAML frontmatter format. Skills live in `skills/` and are Claude Code-compatible. Complex frontmatter structures supported.

---

### Discovery: Load name + description only at startup

**Blog:** `SkillsTool` scans configured skill directories, parses only the `name` and `description` from frontmatter, and embeds them in the tool's description — visible to the LLM without consuming conversation context.

**Herald:** ✅ **Implemented.** `ReloadableSkillsTool` wraps the library's `SkillsTool` and performs the same lightweight registry scan at startup. Only name/description metadata is injected into the tool description.

---

### Semantic Matching: LLM decides when to load a skill

**Blog:** The LLM reads skill descriptions embedded in the `Skill` tool definition, determines if a user request semantically matches, then invokes the `Skill` tool with the skill name as a parameter.

**Herald:** ✅ **Implemented.** Identical mechanism — the LLM autonomously decides when to invoke the Skill tool based on the registry embedded in the tool description. No code-level matching required.

---

### Execution: Load full SKILL.md + optional scripts/references

**Blog:** On invocation, `SkillsTool` loads the full `SKILL.md` content. The LLM can then use `FileSystemTools` (Read) and `ShellTools` (Bash) to access referenced files or run bundled scripts.

**Herald:** 〜 **Partial / Custom Variant.** Full `SKILL.md` content is loaded on invocation. `FileSystemTools` and `ShellTools` are registered and available. However, script execution is mediated through `HeraldShellDecorator`, which enforces security guardrails before any shell call.

---

### Hot Reload — no restart required

**Blog:** The blog series does not describe hot reload. Skills are loaded at startup and re-read from disk on invocation, but no filesystem watch mechanism is specified.

**Herald:** ↗ **Intentional Divergence.** Herald adds `ReloadableSkillsTool`, which wraps `SkillsTool` with a `WatchService` filesystem monitor. Changes to `skills/` trigger a 250ms debounced reload — no bot restart needed. Also accessible via `/skills reload` Telegram command.

---

### Classpath / JAR packaging support

**Blog:** `SkillsTool` supports `addSkillsResource()` using Spring's `Resource` abstraction for classpath loading, useful when distributing skills as part of a JAR/WAR deployment.

**Herald:** ⏳ **Planned.** Herald loads skills from the filesystem only (`skills/`). Classpath loading is not currently implemented. Since Herald runs locally on macOS, this is a lower-priority gap.

---

### Anthropic Native Skills (cloud-sandboxed)

**Blog:** The blog distinguishes Generic Agent Skills (local, model-agnostic) from Anthropic's native Skills API (cloud-sandboxed, pre-built document generation, Anthropic-only). Both can coexist in the same application.

**Herald:** ⏳ **Planned.** Herald uses Generic Agent Skills only. Anthropic's native Skills API (Excel/PowerPoint/Word generation in sandboxed containers) is not yet wired in.

---

## Part 2 — AskUserQuestionTool (Agents That Clarify Before Acting)

> Rather than making assumptions, the agent asks the user structured multiple-choice or free-text questions before producing a response, eliminating guesswork and rework.

### Core Pattern: agent asks before it acts

**Blog:** `AskUserQuestionTool` is a Spring AI tool the LLM invokes when it determines clarification is needed. Each question has: question text, header, 2–4 options with descriptions, and a `multiSelect` flag.

**Herald:** ✅ **Implemented.** Herald's custom `AskUserQuestionTool` implements the same pattern. The LLM invokes it autonomously. Questions support free text, single-select, and multi-select modes.

---

### QuestionHandler interface

**Blog:** `AskUserQuestionTool` accepts a pluggable `QuestionHandler` that presents questions and collects answers. The blog shows console and web (`CompletableFuture` + WebSocket/SSE) implementations.

**Herald:** ✅ **Implemented.** Herald implements `TelegramQuestionHandler`. Questions are formatted and sent to Telegram. A `CompletableFuture` blocks the tool-calling thread with a 5-minute timeout, waiting for the user's Telegram reply. Web/console handlers are not needed given Herald's Telegram-native design.

---

### Async bridge: sync tool API + async UI

**Blog:** The `QuestionHandler` API is synchronous. For async UIs (web), the blog recommends using `CompletableFuture` to bridge: block the tool thread, send to UI, resolve when user submits via REST endpoint.

**Herald:** ✅ **Implemented.** Herald uses exactly this pattern. `TelegramQuestionHandler` uses `CompletableFuture.get()` with a timeout, blocking the Spring AI tool-call thread until Telegram delivers the user's response. The Telegram reply handler resolves the future.

---

### MCP Elicitation relationship

**Blog:** `AskUserQuestionTool` is the agent-local analog of MCP Elicitation. The blog notes Spring AI also supports `@McpElicitation` for server-driven scenarios. `AskUserQuestionTool` does not require an MCP server.

**Herald:** ⏳ **Planned.** Herald uses the agent-local `AskUserQuestionTool` pattern. `@McpElicitation` / MCP server-driven elicitation is not yet implemented, but Herald's existing MCP client connections (Calendar, Gmail) could support it in future.

---

## Part 3 — TodoWriteTool (Structured Task Management)

> LLMs struggle with "lost in the middle" failures on multi-step tasks. `TodoWriteTool` makes the agent's plan explicit and observable, enforcing sequential execution and enabling real-time progress visibility.

### Task decomposition before execution

**Blog:** For tasks requiring 3+ distinct steps, the LLM calls `TodoWriteTool` to create an ordered list before starting work. The tool description itself instructs the LLM on when to use it — self-governing behavior.

**Herald:** ✅ **Implemented.** Herald uses `TodoWriteTool` from `spring-ai-agent-utils`. The self-governing behavior is inherited — the LLM decides when to create a task list based on complexity signals embedded in the tool description.

---

### Todo item lifecycle: pending → in_progress → completed

**Blog:** Each `TodoItem` has `id`, `content`, and `status`. The tool enforces that only one task can be `in_progress` at a time, forcing focused sequential execution. The LLM updates status as work proceeds.

**Herald:** ✅ **Implemented.** Identical lifecycle. The one-in-progress constraint is enforced by the library's `TodoWriteTool` implementation. Herald does not override this behavior.

---

### Real-time progress events

**Blog:** `TodoWriteTool` accepts a `todoEventHandler` callback. The blog shows publishing Spring `ApplicationEvent`s from this handler, allowing listeners to display progress in UIs.

**Herald:** ✅ **Implemented.** Herald's `todoEventHandler` lambda formats each update and dispatches it directly to the `MessageSender` bean (normally `TelegramSender`), so the user sees live task progress (e.g., `[→] Analyzing repo structure  2/4 complete`) as the agent works. When no transport is configured the summary prints to stdout.

---

### Chat memory + ToolCallAdvisor requirement

**Blog:** `TodoWriteTool` requires Chat Memory to retain todo list state across turns. `ToolCallAdvisor` with `conversationHistoryEnabled(false)` ensures all tool messages are logged to memory, replacing built-in tool call history.

**Herald:** ✅ **Implemented.** Herald's advisor chain includes `OneShotMemoryAdvisor` (a custom replacement for Spring AI's `MessageChatMemoryAdvisor` that loads/saves history once per request instead of on every tool-call iteration) backed by JDBC-persisted SQLite storage (windowed at 100 messages). `ToolCallAdvisor` is configured as specified.

---

### System prompt guidance for task management

**Blog:** The blog recommends `MAIN_AGENT_SYSTEM_PROMPT_V2` — a Claude Code-inspired system prompt template with explicit task management instructions — for best results with `TodoWriteTool`.

**Herald:** 〜 **Partial / Custom Variant.** Herald uses a custom system prompt for Ultron (the main agent persona) that includes task management guidance, but does not directly adopt `MAIN_AGENT_SYSTEM_PROMPT_V2`. Herald's prompt is tuned for personal assistant behavior rather than code-agent behavior.

---

## Part 4 — Subagent Orchestration (Hierarchical Agents)

> Instead of one generalist agent, delegate to specialized agents with dedicated context windows, custom system prompts, and model-specific routing. Each subagent executes in isolation and returns only essential results.

### TaskToolCallbackProvider + agent registry

**Blog:** `TaskToolCallbackProvider` loads subagent definitions (`*.md` files in `.claude/agents/`) at startup. Each definition populates an Agent Registry — a catalog of names and descriptions the main agent's LLM uses to decide when to delegate.

**Herald:** ✅ **Implemented.** Herald wires `TaskToolCallbackProvider` with `ClaudeSubagentReferences.fromRootDirectory()` pointing to `.claude/agents/`. The registry is built at startup. Subagent definitions follow the exact format specified in the blog.

---

### Subagent definition file format (*.md with YAML frontmatter)

**Blog:** Each subagent is a Markdown file with frontmatter fields: `name`, `description`, `tools` (allowed), `disallowedTools`, and `model` preference. The system prompt follows the frontmatter. Subagents cannot spawn their own subagents.

**Herald:** ✅ **Implemented.** Herald's `.claude/agents/` contains three custom subagent definitions: **explore** (Sonnet, read-only file/code inspection), **plan** (Sonnet, architecture and design), and **research** (Opus, deep analysis). All follow the specified frontmatter format.

---

### Dedicated context window per subagent

**Blog:** Each subagent executes in its own isolated context window, separate from the main conversation. Only the essential result is returned to the parent agent — intermediate tool calls and reasoning are not surfaced.

**Herald:** ✅ **Implemented.** `TaskTool` from the library handles context isolation automatically. Herald does not override this behavior. Subagent intermediate steps are invisible to the main agent context.

---

### Multi-model routing (haiku / sonnet / opus)

**Blog:** `TaskToolCallbackProvider` supports multiple named `ChatClient.Builder` registrations keyed by model tier. Subagent definitions specify their preferred model; the Task tool routes accordingly.

**Herald:** ✅ **Implemented.** Herald configures multi-model routing: Sonnet for `explore` and `plan` subagents (fast, economical), Opus for the `research` subagent (deep analysis). Model selection is declared in each subagent's frontmatter.

---

### Built-in subagents (Explore, General-Purpose, Plan, Bash)

**Blog:** `spring-ai-agent-utils` ships four built-in subagents auto-registered when `TaskTool` is configured: Explore (read-only codebase), General-Purpose (full access), Plan (architecture), Bash (command execution).

**Herald:** ✅ **Implemented.** Herald uses the four built-in subagents (Explore, General-Purpose, Plan, Bash) auto-registered by `TaskTool`, plus one custom subagent — **research** (Opus, deep analysis with web search) — defined in `.claude/agents/research.md`. Custom agents are loaded via `ClaudeSubagentReferences.fromRootDirectory()` and merged alongside the built-ins.

---

### Parallel + background execution

**Blog:** Multiple subagents can run concurrently. Background tasks execute asynchronously; the main agent can continue while subagents run. `TaskOutputTool` retrieves results when ready. `TaskRepository` supports persistent task storage across instances.

**Herald:** ⏳ **Planned.** Herald uses `TaskTool` and `TaskOutputTool` (both wired) but has not yet exercised parallel or background subagent execution in practice. The library plumbing supports it. Parallel research flows are on the Phase 6 roadmap.

---

## Part 5 — A2A Protocol Integration (Agent2Agent Interoperability)

> The A2A Protocol is an open standard for AI agent communication over HTTP/SSE/JSON-RPC. Agents expose an `AgentCard` at `/.well-known/agent-card.json` for discovery. Spring AI A2A integrates this with `ChatClient` and Spring Boot autoconfiguration.

### A2A Server: expose agent as discoverable endpoint

**Blog:** `spring-ai-a2a-server-autoconfigure` auto-exposes: `POST /` (JSON-RPC sendMessage), `GET /.well-known/agent-card.json`, `GET /card`. `DefaultAgentExecutor` bridges A2A SDK and Spring AI `ChatClient`.

**Herald:** ⏳ **Planned.** Herald does not currently expose an A2A server endpoint. It runs as a single-user Telegram bot. A2A server exposure is planned for potential integration with other agents in the Tanzu demo ecosystem.

---

### A2A Client: discover and call remote agents

**Blog:** A2A clients fetch `AgentCard`s at startup from configured URLs, then register a `sendMessage` `@Tool` with the `ChatClient`. The LLM decides which remote agent to call based on `AgentCard` descriptions. Uses A2A Java SDK Client for HTTP communication.

**Herald:** 〜 **Partial / Custom Variant.** Herald acts as an A2A client but integrates it into the existing `TaskTool` delegation flow rather than a standalone `sendMessage` tool. Configured A2A agents from `herald.yaml` are loaded as `SubagentReference` instances with `A2ASubagentDefinition.KIND`. The `A2ASubagentResolver` fetches their `AgentCard` lazily on first invocation, and `A2ASubagentExecutor` handles the cross-agent HTTP communication.

---

### AgentCard format and .well-known endpoint

**Blog:** `AgentCard` is a standardized JSON document declaring: name, description, URL, version, capabilities, skills (with examples), and protocol version. Exposed at `/.well-known/agent-card.json` per A2A spec.

**Herald:** 〜 **Partial / Custom Variant.** Herald consumes `AgentCard`s via `A2ASubagentResolver` but does not produce one itself since it lacks the A2A server component.

---

### LLM-driven routing across heterogeneous agents

**Blog:** With A2A, the host agent's LLM selects which remote agents to invoke based on their `AgentCard` descriptions — the same semantic matching pattern as Skills and Subagents. Enables routing across agents on different stacks (Python, Node, Java, etc.).

**Herald:** ✅ **Implemented.** A2A agents are seamlessly blended with local subagents in `TaskTool`. The main agent evaluates both local subagent definitions and remote A2A `AgentCard` descriptions to decide where to route tasks.

---

## Part 6 — AutoMemoryTools (Persistent Agent Memory Across Sessions)

> File-based long-term memory that persists across sessions, inspired by Claude Code. The agent writes only facts worth keeping (user preferences, project decisions) into a sandboxed directory with a `MEMORY.md` index.

### Memory System Prompt

**Blog:** Two variants ship in the jar: `AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md` (Options A & B) and `AUTO_MEMORY_FILESYSTEM_TOOLS_SYSTEM_PROMPT.md` (Option C). They instruct the model to read `MEMORY.md` at startup, apply 4 memory types, and use a two-step save process.

**Herald:** ✅ **Implemented.** Uses `AUTO_MEMORY_SYSTEM_PROMPT.md` (equivalent variant) injected by the `AutoMemoryToolsAdvisor`.

---

### Memory Operations and Sandbox

**Blog:** `AutoMemoryTools` provides six purpose-named, sandboxed operations (`MemoryView`, `MemoryCreate`, `MemoryStrReplace`, `MemoryInsert`, `MemoryDelete`, `MemoryRename`) scoped to a configured memory directory. Option C uses generic `FileSystemTools`.

**Herald:** ✅ **Implemented.** Herald uses Options A (sandboxed `AutoMemoryTools`). The 6 tools are dynamically registered per-request by the advisor.

---

### Integration Approach (Options A, B, C)

**Blog:** Option A: zero-boilerplate `AutoMemoryToolsAdvisor`. Option B: Manual setup wiring `AutoMemoryTools` and prompt. Option C: `FileSystemTools` and `ShellTools` with convention-based memory.

**Herald:** ✅ **Implemented.** Herald uses **Option A** (`AutoMemoryToolsAdvisor`). It drops the advisor into the chain at `HIGHEST_PRECEDENCE + 100`, which handles injecting the prompt and registering the 6 tools. (Replaced Herald's previous custom `MemoryMdAdvisor` and SQLite implementation).

---

### Memory Consolidation

**Blog:** `AutoMemoryToolsAdvisor` accepts a `memoryConsolidationTrigger` predicate. When `true`, it injects a consolidation reminder into the system prompt to merge duplicates and drop outdated facts.

**Herald:** ↗ **Intentional Divergence.** Herald configures the trigger as `(req, instant) -> false`. It relies on manual or proactive (Cron) consolidation rather than injecting a silent automatic trigger on random user requests, keeping generation latency predictable.

---

## Herald-Specific Additions — Beyond the Blog Series

> Herald extends the blog series architecture with capabilities specific to a personal assistant context. These are not covered in the five-part series.

### Advisor Chain — Dynamic Context Injection

**Blog:** The blog series mentions `AgentEnvironment` for runtime context injection but does not prescribe a multi-advisor chain pattern.

**Herald:** ↗ **Herald Extension.** A 5-layer `CallAdvisor` chain enriches every prompt:

| Advisor | What It Injects |
|---|---|
| `DateTimePromptAdvisor` | Current date/time and timezone into system prompt placeholders |
| `ContextMdAdvisor` | Standing brief from `~/.herald/CONTEXT.md`, re-read on every turn |
| `AutoMemoryToolsAdvisor` | All persistent file-based memories |
| `ContextCompactionAdvisor` | Auto-compacts conversation history when approaching token limits |
| `OneShotMemoryAdvisor` | JDBC-backed conversation history (windowed, 100 messages) — loads/saves once per request, not per tool-call iteration |

---

### Proactive Scheduling (Cron Jobs)

**Blog:** The blog series covers reactive agent patterns only — agents respond to user input.

**Herald:** ↗ **Herald Extension.** A `CronService` runs agent prompts on schedules. Morning briefings, reminders, and other proactive outreach are defined as cron jobs in SQLite, manageable via `/cron` commands or the Vue 3 console. The agent initiates Telegram messages without user input.

---

### MCP Client Integration (Google Calendar + Gmail)

**Blog:** The blog series notes that Agent Skills work well with Spring AI MCP features, but does not implement MCP client connections as part of the pattern set.

**Herald:** ↗ **Herald Extension.** Herald connects to Google Calendar and Gmail as MCP servers via `GCAL_MCP_URL` and `GMAIL_MCP_URL` configuration. Tools from these servers are available for reading/writing calendar events and email. `GwsTools` also provides an alternative path via the `gws` CLI.

---

### Multi-Model Runtime Switching

**Blog:** The blog series covers model routing for subagents (via multi-model `TaskToolCallbackProvider`). The main agent model is fixed at configuration time.

**Herald:** ↗ **Herald Extension.** The main agent's model can be switched at runtime via `/model <provider> <model>`. The override is persisted in SQLite and survives restarts. Supports switching between Anthropic, OpenAI, Ollama, and Gemini without redeployment.

---

### Management Console (Vue 3 Web UI)

**Blog:** The blog series does not address operational tooling or management interfaces.

**Herald:** ↗ **Herald Extension.** A separate `herald-ui` service (Spring Boot + Vue 3 + Tailwind) exposes a management console at port 8080. Features: skills editor, memory viewer/editor, cron job manager, conversation history viewer, SSE-driven live status. Shares the same SQLite database as `herald-bot`.

---

### Security Guardrails (Shell Decorator)

**Blog:** The blog series notes script execution runs without sandboxing, recommending containerization for safety. No built-in guardrails are described.

**Herald:** ↗ **Herald Extension.** All shell execution is wrapped in `HeraldShellDecorator`, which enforces an allowlist/denylist of commands, rate limits, and command logging before passing to `ShellTools`. Herald's alternative to full containerization, appropriate for a single-user personal assistant.

---

## Summary — Pattern Coverage at a Glance

| Pattern | Blog Features | ✅ Adopted | 〜 Custom Variant | ↗ Herald-Only | ⏳ Planned |
|---|---|---|---|---|---|
| Part 1: Agent Skills | 7 | 3 (format, discovery, matching) | 1 (execution w/ guardrails) | 1 (hot reload) | 2 (classpath, native Skills) |
| Part 2: AskUserQuestion | 4 | 3 (core, handler, async bridge) | 1 (Telegram handler) | — | 1 (MCP Elicitation) |
| Part 3: TodoWrite | 5 | 4 (decomp, lifecycle, events, memory) | 1 (custom system prompt) | — | — |
| Part 4: Subagents | 6 | 5 (provider, format, context, multi-model, built-in agents) | — | 1 (research subagent) | 1 (parallel/background) |
| Part 5: A2A Protocol | 4 | 1 (LLM-driven routing) | 2 (A2A Client, AgentCard format) | — | 1 (A2A Server) |
| Part 6: AutoMemoryTools | 4 | 3 (Prompt, Sandbox, Integration) | — | 1 (Consolidation trigger) | — |
| Herald-Only Extensions | N/A | — | — | 6 (advisor chain, cron, MCP, runtime model switch, console, shell guardrails) | — |

---

*Document generated April 2026 • dbbaskette/herald*
