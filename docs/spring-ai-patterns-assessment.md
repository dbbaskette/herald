# Herald — Spring AI Patterns Implementation Assessment

> Comprehensive comparison of Herald against the Spring AI blog series, Spring AI 2.0 reference docs, and community patterns

**dbbaskette/herald  •  March 2026  •  Commit `63f81e4`**

---

**Legend:**
- ✅ **Implemented** — matches the documented pattern
- 〜 **Partial / Custom Variant** — adopted with meaningful differences
- ⏳ **Planned** — on the roadmap, not yet done
- ↗ **Intentional Divergence** — Herald went a different direction by design
- ❌ **Not Applicable** — pattern doesn't fit Herald's use case

---

## Table of Contents

1. [Spring AI Agentic Patterns Blog Series (5 Parts)](#1-spring-ai-agentic-patterns-blog-series-5-parts)
2. [Building Effective Agents — Workflow Patterns](#2-building-effective-agents--workflow-patterns)
3. [Additional Blog Posts — Advanced Features](#3-additional-blog-posts--advanced-features)
4. [Spring AI 2.0 Reference — Core APIs](#4-spring-ai-20-reference--core-apis)
5. [Spring AI 2.0 Reference — RAG](#5-spring-ai-20-reference--rag)
6. [Spring AI 2.0 Reference — MCP Integration](#6-spring-ai-20-reference--mcp-integration)
7. [Herald-Specific Extensions](#7-herald-specific-extensions)
8. [Summary Scorecard](#8-summary-scorecard)
9. [Architectural Direction: Dual-Mode Agent Runtime](#9-architectural-direction-dual-mode-agent-runtime)
10. [Recommended Next Steps](#10-recommended-next-steps)

---

## 1. Spring AI Agentic Patterns Blog Series (5 Parts)

Source: [spring-ai-agent-utils](https://github.com/spring-ai-community/spring-ai-agent-utils) library, blog series by Christian Tzolov (Jan 2026)

### Part 1 — Agent Skills ([Blog](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/))

Skills are markdown files with YAML frontmatter that give an agent domain knowledge on demand via progressive disclosure.

| Feature | Blog Pattern | Herald Status | Notes |
|---------|-------------|---------------|-------|
| SKILL.md format with YAML frontmatter | `name` + `description` required, folder structure | ✅ | `skills/*/SKILL.md`, Claude Code-compatible |
| Lightweight discovery at startup | `SkillsTool` scans dirs, embeds name/description in tool description | ✅ | `ReloadableSkillsTool` wraps library `SkillsTool` |
| LLM semantic matching | LLM reads skill registry in tool description, decides when to load | ✅ | Identical mechanism |
| Full content loading on invocation | `SkillsTool` loads full SKILL.md, agent uses FileSystem/Shell for scripts | 〜 | Full loading works; shell access mediated through `HeraldShellDecorator` security layer |
| Classpath/JAR packaging | `addSkillsResource()` with Spring `Resource` abstraction | ⏳ | Filesystem-only loading; low priority for single-machine deployment |
| Anthropic Native Skills API | Cloud-sandboxed document generation (Excel/Word/PPT) | ⏳ | Not wired; depends on Anthropic SDK support |
| **Hot reload (Herald extension)** | Not in blog | ↗ | `ReloadableSkillsTool` + `WatchService` with 250ms debounce; no restart needed |

**Coverage: 3/6 ✅, 1 〜, 2 ⏳, 1 ↗ extension**

---

### Part 2 — AskUserQuestionTool ([Blog](https://spring.io/blog/2026/01/16/spring-ai-ask-user-question-tool/))

Agents that clarify before acting — structured multiple-choice or free-text questions during execution.

| Feature | Blog Pattern | Herald Status | Notes |
|---------|-------------|---------------|-------|
| Core tool pattern | LLM invokes `AskUserQuestionTool` when clarification needed | ✅ | Same autonomous invocation pattern |
| QuestionHandler interface | Pluggable handler for presenting questions and collecting answers | ✅ | `TelegramQuestionHandler` implementation |
| Async bridge (CompletableFuture) | Sync tool API bridged to async UI via CompletableFuture | ✅ | `CompletableFuture.get()` with 5-min timeout, Telegram reply resolves |
| MCP Elicitation relationship | `@McpElicitation` for server-driven elicitation; coexists with agent-local tool | ⏳ | Agent-local only; MCP elicitation not yet implemented |

**Coverage: 3/4 ✅, 1 ⏳**

---

### Part 3 — TodoWriteTool ([Blog](https://spring.io/blog/2026/01/20/spring-ai-agentic-patterns-todowrite/))

Externalized working memory for multi-step tasks — prevents "lost in the middle" failures.

| Feature | Blog Pattern | Herald Status | Notes |
|---------|-------------|---------------|-------|
| Task decomposition before execution | LLM creates ordered list for 3+ step tasks; self-governing via tool description | ✅ | Uses `TodoWriteTool` from spring-ai-agent-utils |
| Todo lifecycle: pending → in_progress → completed | One-in-progress constraint enforced | ✅ | Library constraint inherited |
| Real-time progress events | `todoEventHandler` callback → `MessageSender` | ✅ | Lambda dispatches formatted summary directly to Telegram (e.g. `[→] Analyzing... 2/4`) or stdout fallback |
| Chat memory + ToolCallAdvisor requirement | TodoWriteTool requires chat memory; ToolCallAdvisor logs tool messages | ✅ | `OneShotMemoryAdvisor` + JDBC-backed SQLite + `ToolCallAdvisor` |
| System prompt template (V2) | Blog recommends `MAIN_AGENT_SYSTEM_PROMPT_V2` (Claude Code-inspired) | 〜 | Herald uses custom personal assistant prompt instead |

**Coverage: 4/5 ✅, 1 〜**

---

### Part 4 — Subagent Orchestration ([Blog](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents))

Hierarchical delegation to specialized agents with dedicated context windows and model-specific routing.

| Feature | Blog Pattern | Herald Status | Notes |
|---------|-------------|---------------|-------|
| TaskToolCallbackProvider + agent registry | Loads `*.md` from `.claude/agents/`, builds registry at startup | ✅ | `ClaudeSubagentReferences.fromRootDirectory()` |
| Subagent definition format | Markdown with YAML frontmatter: name, description, tools, model | ✅ | 3 custom agents: explore (Sonnet), plan (Sonnet), research (Opus) |
| Dedicated context window per subagent | Isolated execution; only result returned to parent | ✅ | Library `TaskTool` handles isolation |
| Multi-model routing | Named `ChatClient.Builder` registrations per tier | ✅ | haiku/sonnet/opus/openai/ollama/gemini tiers configured |
| Built-in subagents | Explore, General-Purpose, Plan, Bash auto-registered | ✅ | 4 built-in + 3 custom agents |
| Parallel/background execution | Multiple subagents run concurrently; `TaskOutputTool` retrieves results | ⏳ | Plumbing exists but not exercised in practice |

**Coverage: 5/6 ✅, 1 ⏳**

---

### Part 5 — A2A Protocol ([Blog](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration/))

Agent-to-Agent interoperability over HTTP/SSE/JSON-RPC for cross-system agent communication.

| Feature | Blog Pattern | Herald Status | Notes |
|---------|-------------|---------------|-------|
| A2A Server endpoint | `spring-ai-a2a-server-autoconfigure`, expose `/.well-known/agent-card.json` | ⏳ | Not implemented; Herald is single-user Telegram bot |
| A2A Client | Fetch remote AgentCards, register `sendMessage` tool | ⏳ | Delegation uses in-process TaskTool, not A2A protocol |
| AgentCard definition | JSON: name, description, URL, capabilities, skills | ⏳ | No AgentCard defined yet |
| LLM-driven cross-agent routing | Semantic selection of remote agents from AgentCard descriptions | ⏳ | Architecturally consistent with existing delegation chain |

**Coverage: 0/4 ✅, 4 ⏳**

---

## 2. Building Effective Agents — Workflow Patterns

Source: [Building Effective Agents (Part 1)](https://spring.io/blog/2025/01/21/spring-ai-agentic-patterns/) and [Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)

Five fundamental agentic workflow patterns from the Anthropic research publication, implemented in Spring AI.

| Pattern | Description | Herald Status | Notes |
|---------|-------------|---------------|-------|
| **Chain Workflow** | Sequential steps, each LLM call builds on the previous output | 〜 | Herald's advisor chain is a form of chaining (DateTime → Context → Memory → Compaction → History). Not used as a standalone workflow pattern. |
| **Parallelization Workflow** | Concurrent processing of independent subtasks, aggregated programmatically | ⏳ | Subagent parallel execution is wired but not exercised. No standalone parallelization workflow. |
| **Routing Workflow** | Classify input, route to specialized handler with dedicated prompt | 〜 | `ModelSwitcher` provides model-level routing. Skills provide domain-level routing. No formal `RoutingWorkflow` class. |
| **Orchestrator-Workers** | Central LLM decomposes tasks, delegates to worker LLMs | ✅ | `TaskTool` + subagent registry implements this pattern. Main agent decomposes, delegates to explore/plan/research workers. |
| **Evaluator-Optimizer** | One LLM generates, another evaluates in a loop until passing | ⏳ | Not implemented. No self-evaluation or iterative refinement loop. Could use `ContextCompactionAdvisor`'s pattern as a starting point. |

**Coverage: 1/5 ✅, 2 〜, 2 ⏳**

---

## 3. Additional Blog Posts — Advanced Features

### Tool Argument Augmenter ([Blog](https://spring.io/blog/2025/12/23/spring-ai-tool-argument-augmenter-tzolov/))

Capture LLM reasoning, confidence, and metadata during tool calls without modifying tool implementations.

| Feature | Description | Herald Status | Notes |
|---------|-------------|---------------|-------|
| ToolArgsAugmenter interface | Non-invasive schema augmentation; LLM sees extra fields, tool doesn't | ⏳ | Not implemented. Would be valuable for logging reasoning behind shell commands or memory operations. |
| Reasoning capture | LLM explains why it's calling a tool | ⏳ | No reasoning capture on tool calls currently |
| Confidence scoring | LLM provides confidence level per tool invocation | ⏳ | Not implemented |

**Coverage: 0/3, 3 ⏳**

---

### ToolSearchToolCallAdvisor ([Blog](https://spring.io/blog/2025/12/11/spring-ai-tool-search-tools-tzolov/))

Dynamic tool discovery achieving 34-64% token savings by sending only matching tool definitions to the LLM.

| Feature | Description | Herald Status | Notes |
|---------|-------------|---------------|-------|
| Lucene-based tool indexing | Indexes all tools at startup, matches per-turn | ✅ | `ToolSearchToolCallAdvisor` from spring-ai-agent-utils wired in advisor chain |
| Context reduction | Only matching tool definitions sent to LLM | ✅ | ~64% context reduction reported with 40 tools |
| Replaces ToolCallAdvisor | Drop-in replacement with search semantics | ✅ | Configured at `LOWEST_PRECEDENCE - 1` in advisor chain |

**Coverage: 3/3 ✅**

---

### Recursive Advisors ([Blog](https://spring.io/blog/2025/11/04/spring-ai-recursive-advisors/))

Advisors that loop through the downstream chain multiple times until a condition is met.

| Feature | Description | Herald Status | Notes |
|---------|-------------|---------------|-------|
| CallAdvisorChain.copy() for sub-chain iteration | Controlled iteration without re-executing upstream advisors | ⏳ | Not used. Could enhance evaluator-optimizer pattern. |
| ReReadingAdvisor (RE² technique) | Augments prompt with "Read the question again" for better reasoning | ⏳ | Not implemented. Low priority — Claude models have strong reasoning baseline. |
| Custom recursive patterns | Self-improving loops with exit conditions | ⏳ | Not implemented |

**Coverage: 0/3, 3 ⏳**

---

### Spring AI Agents & Bench ([Blog](https://spring.io/blog/2025/10/28/agents-and-benchmarks/))

Agent abstractions and benchmarking framework.

| Feature | Description | Herald Status | Notes |
|---------|-------------|---------------|-------|
| Spring AI Bench | Standardized benchmarking for agent performance | ⏳ | Not used. Herald has `AgentMetrics` for internal tracking but no benchmark suite. |
| Agent lifecycle abstractions | Formal agent state machine | 〜 | Herald uses ChatClient + advisor chain rather than formal agent lifecycle APIs |

**Coverage: 0/2 ✅, 1 〜, 1 ⏳**

---

### Prompt Engineering Techniques ([Blog](https://spring.io/blog/2025/04/14/spring-ai-prompt-engineering-patterns/))

| Feature | Description | Herald Status | Notes |
|---------|-------------|---------------|-------|
| System prompt templating | Parameterized prompts with `PromptTemplate` | ✅ | `MAIN_AGENT_SYSTEM_PROMPT.md` with `{current_datetime}`, `{timezone}` placeholders |
| Few-shot examples | Examples in system/user prompts | 〜 | Skills contain examples; main system prompt does not use structured few-shot patterns |
| Chain-of-thought prompting | Explicit reasoning steps in prompt | 〜 | System prompt encourages structured reasoning but doesn't enforce CoT format |

**Coverage: 1/3 ✅, 2 〜**

---

## 4. Spring AI 2.0 Reference — Core APIs

### ChatClient API

| Feature | Reference Doc | Herald Status | Notes |
|---------|--------------|---------------|-------|
| ChatClient.Builder fluent API | Builder pattern for prompts, advisors, tools | ✅ | `HeraldAgentConfig` uses builder extensively |
| `.defaultAdvisors()` | Register advisors at build time | ✅ | 8+ advisors registered via builder |
| `.defaultTools()` / `.defaultToolCallbacks()` | Register tools at build time | ✅ | 40 tools registered |
| `.defaultSystem()` | System prompt template | ✅ | `MAIN_AGENT_SYSTEM_PROMPT.md` loaded as resource |
| Streaming (`.stream()`) | Reactive response streaming | ⏳ | Herald uses `.call()` only; streaming planned for UX improvement |
| Structured output (`.entity()`) | Type-safe response deserialization | 〜 | Used in some contexts (e.g., compaction) but not broadly |

**Coverage: 4/6 ✅, 1 〜, 1 ⏳**

---

### Advisor API

| Feature | Reference Doc | Herald Status | Notes |
|---------|--------------|---------------|-------|
| CallAdvisor interface | Synchronous request/response interception | ✅ | 7 custom CallAdvisors implemented |
| StreamAdvisor interface | Reactive streaming interception | ⏳ | Not implemented; depends on streaming adoption |
| Advisor ordering (Ordered interface) | Precedence-based chain execution | ✅ | Carefully ordered from `HIGHEST_PRECEDENCE+50` to `LOWEST_PRECEDENCE-1` |
| adviseContext (shared state) | Cross-advisor state sharing | 〜 | Limited use; most state is passed through prompt mutation |
| Built-in: MessageChatMemoryAdvisor | Message-based chat memory | ↗ | Replaced by custom `OneShotMemoryAdvisor` to fix duplicate message bug |
| Built-in: VectorStoreChatMemoryAdvisor | Vector store-backed memory retrieval | ⏳ | No vector store in Herald |
| Built-in: SafeGuardAdvisor | Content safety filtering | ⏳ | Not used; Herald relies on model safety + shell blocklist |
| Built-in: ReReadingAdvisor | RE² reasoning improvement | ⏳ | Not used |

**Coverage: 2/8 ✅, 1 〜, 1 ↗, 4 ⏳**

---

### Tool Calling

| Feature | Reference Doc | Herald Status | Notes |
|---------|--------------|---------------|-------|
| `@Tool` + `@ToolParam` annotations | Declarative tool definitions | ✅ | All 40 tools use annotations with descriptions |
| ToolCallAdvisor | Automatic tool execution loop | ✅ | Via `ToolSearchToolCallAdvisor` (drop-in replacement) |
| Dynamic tool registration | Runtime tool addition | 〜 | Skills add capabilities dynamically; tools themselves are static |
| MCP tool integration | External tools via MCP protocol | 〜 | `GwsTools` wraps CLI instead of MCP; MCP client deps included but not active |
| Tool callbacks | `ToolCallback` / `FunctionCallback` pattern | ✅ | `TaskToolCallbackProvider` uses this pattern |

**Coverage: 3/5 ✅, 2 〜**

---

### Chat Memory

| Feature | Reference Doc | Herald Status | Notes |
|---------|--------------|---------------|-------|
| ChatMemory interface | Storage abstraction for conversation history | ✅ | `MessageWindowChatMemory` with JDBC backend |
| MessageWindowChatMemory | Sliding window over recent messages | ✅ | Windowed at 20 messages |
| JdbcChatMemoryRepository | JDBC-backed persistence | ✅ | SQLite via `SPRING_AI_CHAT_MEMORY` table |
| In-memory ChatMemory | Session-scoped, non-persistent | ↗ | Skipped in favor of persistent JDBC |
| VectorStoreChatMemory | Semantic search over past conversations | ⏳ | Not implemented; Obsidian cold storage serves similar purpose |

**Coverage: 3/5 ✅, 1 ↗, 1 ⏳**

---

## 5. Spring AI 2.0 Reference — RAG

### Retrieval Augmented Generation

Herald does **not** implement RAG. This section documents what's available and where it could add value.

| Feature | Reference Doc | Herald Status | Notes |
|---------|--------------|---------------|-------|
| QuestionAnswerAdvisor | Naive RAG with vector store similarity search | ❌ | No vector store; Herald uses hot memory + skills for knowledge |
| RetrievalAugmentationAdvisor | Modular RAG framework (query transform → retrieve → augment) | ❌ | Not applicable currently |
| VectorStore abstraction | Unified interface for Pgvector, Chroma, Pinecone, etc. | ❌ | No vector database |
| Document loaders/transformers | ETL pipeline for document ingestion | ❌ | No document ingestion pipeline |
| Query transformers | Compression, rewrite, translation | ❌ | Not applicable |
| Multi-query expansion | Generate multiple query variations for better recall | ❌ | Not applicable |

**Potential value:** RAG could enhance Herald if a personal knowledge base grows beyond what hot memory + Obsidian can handle. The `RetrievalAugmentationAdvisor` could replace `MemoryBlockAdvisor` for semantic retrieval over a larger fact store.

**Coverage: 0/6 — intentionally not implemented**

---

## 6. Spring AI 2.0 Reference — MCP Integration

### Model Context Protocol

| Feature | Reference Doc | Herald Status | Notes |
|---------|--------------|---------------|-------|
| MCP Client Boot Starter | `spring-ai-starter-mcp-client` | 〜 | `mcp-json-jackson3` dep included; `GCAL_MCP_URL` / `GMAIL_MCP_URL` env vars defined but `GwsTools` CLI wrapper used instead |
| MCP Server Boot Starter | `spring-ai-starter-mcp-server` | ⏳ | Not exposing Herald as MCP server |
| `@McpTool` annotations | Expose Java methods as MCP tools | ⏳ | Herald tools use `@Tool`, not `@McpTool` |
| `@McpResource` | URI-based resource exposure | ⏳ | Not implemented |
| `@McpElicitation` | Server-driven user question flow | ⏳ | Uses agent-local `AskUserQuestionTool` instead |
| `@McpPrompt` | Prompt template provision | ⏳ | Not implemented |
| Transport: STDIO | Process-based communication | ❌ | Not needed for Herald's use case |
| Transport: SSE/Streamable-HTTP | HTTP-based streaming | 〜 | Configured for Calendar/Gmail MCP but `GwsTools` used in practice |
| Annotations module | `mcp-annotations` for declarative MCP | ⏳ | Not adopted |

**Coverage: 0/9 ✅, 2 〜, 6 ⏳, 1 ❌**

---

## 7. Herald-Specific Extensions

These capabilities go beyond the blog series and Spring AI reference patterns.

| Extension | Description | Status |
|-----------|-------------|--------|
| **Multi-layer Advisor Chain** | 8+ advisors: DateTime → ContextMd → MemoryBlock → Compaction → OneShotMemory → PromptDump → ToolPairSanitizing → ToolSearchToolCall | ✅ |
| **Hot Memory (SQLite KV store)** | `MemoryTools` (7 operations) + `MemoryBlockAdvisor` injects facts every turn | ✅ |
| **Cold Memory (Obsidian)** | Long-form research, session logs, archived conversations via Obsidian vault | ✅ |
| **Context Compaction** | LLM-based conversation summarization at 80% token ceiling, archives to Obsidian | ✅ |
| **Proactive Scheduling** | `CronService` runs agent prompts on schedule (morning briefings, reminders) | ✅ |
| **Runtime Model Switching** | Switch Anthropic/OpenAI/Ollama/Gemini at runtime, persisted to DB | ✅ |
| **Management Console** | Vue 3 SPA: chat, memory, cron, skills editor, conversations, settings | ✅ |
| **Shell Security** | `HeraldShellDecorator`: regex blocklist, sudo confirmation via Telegram, timeouts | ✅ |
| **Telegram Integration** | Full bot with inline questions, progress updates, commands, proactive outreach | ✅ |
| **Google Workspace** | Gmail + Calendar via `gws` CLI wrapper (`GwsTools`) | ✅ |
| **OneShotMemoryAdvisor** | Fixes Spring AI 2.0 duplicate message bug from ToolCallAdvisor loops | ✅ |
| **ToolPairSanitizingAdvisor** | Cleans orphaned tool_use/tool_result pairs from JDBC serialization | ✅ |
| **PromptDumpAdvisor** | Full prompt diagnostic dumps for token analysis | ✅ |

---

## 8. Summary Scorecard

### Blog Series Coverage

| Source | Total Features | ✅ Implemented | 〜 Custom | ⏳ Planned | ↗ Divergent | ❌ N/A |
|--------|---------------|---------------|-----------|-----------|-------------|--------|
| Part 1: Agent Skills | 6 | 3 | 1 | 2 | — | — |
| Part 2: AskUserQuestion | 4 | 3 | — | 1 | — | — |
| Part 3: TodoWrite | 5 | 4 | 1 | — | — | — |
| Part 4: Subagents | 6 | 5 | — | 1 | — | — |
| Part 5: A2A Protocol | 4 | 0 | — | 4 | — | — |
| **Series Total** | **25** | **15 (60%)** | **2 (8%)** | **8 (32%)** | — | — |

### Broader Spring AI Ecosystem

| Source | Total Features | ✅ | 〜 | ⏳ | ↗ | ❌ |
|--------|---------------|-----|-----|-----|-----|-----|
| Effective Agent Workflows | 5 | 1 | 2 | 2 | — | — |
| Tool Argument Augmenter | 3 | 0 | — | 3 | — | — |
| ToolSearchToolCallAdvisor | 3 | 3 | — | — | — | — |
| Recursive Advisors | 3 | 0 | — | 3 | — | — |
| Agents & Bench | 2 | 0 | 1 | 1 | — | — |
| Prompt Engineering | 3 | 1 | 2 | — | — | — |
| ChatClient API | 6 | 4 | 1 | 1 | — | — |
| Advisor API | 8 | 2 | 1 | 4 | 1 | — |
| Tool Calling | 5 | 3 | 2 | — | — | — |
| Chat Memory | 5 | 3 | — | 1 | 1 | — |
| RAG | 6 | 0 | — | — | — | 6 |
| MCP | 9 | 0 | 2 | 6 | — | 1 |
| **Ecosystem Total** | **58** | **17 (29%)** | **11 (19%)** | **21 (36%)** | **2 (3%)** | **7 (12%)** |

### Overall

| Category | Count | % |
|----------|-------|---|
| ✅ Implemented (blog + ecosystem) | 32 | 39% |
| 〜 Custom Variant | 13 | 16% |
| ⏳ Planned / Gap | 29 | 35% |
| ↗ Intentional Divergence | 2 | 2% |
| ❌ Not Applicable | 7 | 8% |
| **Herald-Only Extensions** | **13** | — |

**Effective implementation rate (✅ + 〜 out of applicable features): 45 / 76 = 59%**

---

## 9. Architectural Direction: Dual-Mode Agent Runtime

### The Vision

Herald should operate in **two distinct modes**, making the persistent memory layer (SQLite, Obsidian, Telegram, cron) entirely optional. The core value — a Spring AI agentic loop driven by an `agents.md` definition file — should be usable standalone in any context.

### Mode 1: Ephemeral / Sandbox Agent (New)

**Use case:** "I want to pass an `agents.md` and have it process Cloud Foundry data, write a spreadsheet, analyze logs, generate a report — then exit."

**What it provides:**
- ChatClient + full advisor chain (minus persistence-dependent advisors)
- ToolSearchToolCallAdvisor for dynamic tool discovery
- All stateless tools: FileSystemTools, ShellTools, WebTools, SkillsTool
- Subagent delegation via TaskTool (if agents are defined)
- TodoWriteTool for multi-step task tracking (in-memory only)
- AskUserQuestionTool with a console or callback-based QuestionHandler
- Configurable model provider (Anthropic, OpenAI, Ollama, Gemini)

**What it does NOT require:**
- SQLite database or any JDBC dependency
- Telegram bot token or Telegram integration
- Obsidian vault
- Cron scheduler
- Herald UI / management console
- MemoryTools / MemoryBlockAdvisor / hot memory
- OneShotMemoryAdvisor (no conversation history across sessions)

**How it's configured:**
```
agents.md → defines persona, system prompt, allowed tools, model preference
```
No environment variables required beyond an API key for the chosen LLM provider.

**Possible entry points:**
- CLI: `java -jar herald-agent.jar --agents=./my-agents.md --prompt="Analyze CF spaces"`
- Programmatic: `HeraldAgentFactory.fromAgentsFile(Path.of("agents.md")).chat("...")`
- REST: Lightweight HTTP endpoint that accepts prompts and returns responses

### Mode 2: Persistent Agent (Current)

**Use case:** "Always-on personal assistant in Telegram with memory, scheduling, and a management console."

Everything Herald does today. Activates when persistence config is detected (SQLite path, Telegram token, etc.).

### Implementation Plan

#### Phase 1: Extract Core Agent Loop (Medium effort)

| Step | Description |
|------|-------------|
| 1a | **Create `herald-core` module** — Extract the agentic loop (ChatClient builder, advisor chain, tool registration, model switching) into a new Maven module with zero persistence dependencies. |
| 1b | **Define `AgentProfile` from agents.md** — Parse a top-level `agents.md` file (YAML frontmatter + markdown system prompt) into an `AgentProfile` record: persona, model, tools, system prompt, subagent references. This is the single config file for ephemeral mode. |
| 1c | **Make advisors conditional** — `MemoryBlockAdvisor`, `OneShotMemoryAdvisor`, `ContextCompactionAdvisor` only activate when persistence beans are present. `DateTimePromptAdvisor`, `ContextMdAdvisor`, `ToolSearchToolCallAdvisor` always activate. |
| 1d | **Create `AgentFactory`** — Factory that builds a ready-to-use `ChatClient` from an `AgentProfile`. No Spring context required for simple cases; Spring-managed for DI-heavy cases. |

#### Phase 2: Ephemeral Runtime (Medium effort)

| Step | Description |
|------|-------------|
| 2a | **CLI runner** — Spring Boot `CommandLineRunner` that reads `--agents=` and `--prompt=` args, runs a single agent interaction, prints the result, and exits. Suitable for scripting and pipelines. |
| 2b | **In-memory ChatMemory** — When no JDBC is configured, use Spring AI's in-memory `MessageWindowChatMemory`. Conversation persists within the process lifetime only. |
| 2c | **Console QuestionHandler** — Stdin-based `QuestionHandler` for `AskUserQuestionTool` when Telegram is absent. |
| 2d | **TodoWriteTool with console output** — In-memory todo state with stdout progress (no Telegram dependency). |
| 2e | **Conditional bean loading** — Use `@ConditionalOnProperty` / `@ConditionalOnBean` to gate all persistence, Telegram, cron, and UI beans. |

#### Phase 3: Module Split (Larger effort)

| Module | Contents | Dependencies |
|--------|----------|-------------|
| `herald-core` | ChatClient factory, advisor chain, tool interfaces, AgentProfile, model switching | Spring AI, spring-ai-agent-utils |
| `herald-persistence` | SQLite, JDBC repos, MemoryTools, MemoryBlockAdvisor, OneShotMemoryAdvisor, CronService | herald-core, spring-data-jdbc, sqlite-jdbc |
| `herald-telegram` | TelegramPoller, TelegramSender, TelegramQuestionHandler, CommandHandler | herald-core, java-telegram-bot-api |
| `herald-bot` | Spring Boot app wiring all modules together for persistent mode | herald-core, herald-persistence, herald-telegram |
| `herald-cli` | Ephemeral CLI runner, console I/O | herald-core |
| `herald-ui` | Vue 3 management console (unchanged) | herald-persistence |

#### Phase 4: agents.md Format Specification

```markdown
---
name: cf-analyzer
description: Analyzes Cloud Foundry environments and generates reports
model: sonnet                          # or opus, haiku, openai, ollama, gemini
provider: anthropic                    # default provider
tools:                                 # which tool categories to enable
  - filesystem                         # FileSystemTools
  - shell                              # ShellTools (with security decorator)
  - web                                # WebTools (fetch + search)
  - skills                             # SkillsTool (if skills/ dir exists)
skills_directory: ./skills             # optional, relative to agents.md
subagents_directory: ./.claude/agents  # optional, for TaskTool delegation
memory: false                          # no persistent memory
context_file: ./CONTEXT.md             # optional standing brief
max_tokens: 200000                     # context window ceiling
---

You are a Cloud Foundry operations analyst. You have access to the `cf` CLI
and can read/write files on disk.

## Your Capabilities
- Query CF spaces, apps, services, and routes
- Generate CSV/Excel reports from CF data
- Compare environments (dev vs prod)
- Identify configuration drift

## Rules
- Always confirm before making changes to CF environments
- Write reports to ./output/ directory
- Use `cf target` to verify the current org/space before any operation
```

**Key design decisions:**
- `memory: false` (default) = ephemeral mode, no SQLite
- `memory: true` = activate persistence layer (requires `herald-persistence` on classpath)
- Tool categories are opt-in, not opt-out (secure by default)
- System prompt is the markdown body (same as subagent definitions)
- Compatible with existing `.claude/agents/*.md` format for subagents

---

## 10. Recommended Next Steps

### Priority 0: Dual-Mode Foundation

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 0a | **Extract `herald-core` module** | Medium | Unlocks ephemeral mode; clean dependency separation |
| 0b | **`@ConditionalOnProperty` gating** for all persistence beans | Small | Existing code runs without SQLite when property absent |
| 0c | **`AgentProfile` from agents.md** parser | Small | Single-file agent definition for ephemeral mode |
| 0d | **CLI runner** (`--agents=` + `--prompt=`) | Small | First working ephemeral execution path |

### Priority 1: Quick Wins

| # | Item | Source | Effort | Impact |
|---|------|--------|--------|--------|
| 1 | **Parallel subagent execution** | Blog Part 4 | Small | Test existing plumbing; unlocks concurrent research flows |
| 2 | **Classpath skill packaging** | Blog Part 1 | Small | `SkillsTool.addSkillsResource()` — enables JAR-bundled skills |
| 3 | **Tool Argument Augmenter** | Blog Dec 2025 | Small | Add reasoning capture to shell/memory tools for observability |

### Priority 2: Medium Effort

| # | Item | Source | Effort | Impact |
|---|------|--------|--------|--------|
| 4 | **Streaming responses** | Spring AI 2.0 ChatClient | Medium | Biggest UX improvement — SSE to Telegram + web console |
| 5 | **A2A Server** | Blog Part 5 | Medium | Expose Herald as discoverable agent; completes blog series coverage |
| 6 | **MCP Elicitation** | Blog Part 2 / Spring AI MCP | Medium | Server-driven question flow for Calendar/Gmail MCP servers |

### Priority 3: Strategic

| # | Item | Source | Effort | Impact |
|---|------|--------|--------|--------|
| 7 | **Full module split** (Phase 3) | Architecture | Large | Clean separation; herald-core publishable as library |
| 8 | **Evaluator-Optimizer workflow** | Effective Agents blog | Large | Self-improving responses; quality gate for complex tasks |
| 9 | **RAG with VectorStore** | Spring AI 2.0 Reference | Large | Semantic retrieval over growing knowledge base |
| 10 | **Expose Herald as MCP Server** | Spring AI MCP Reference | Medium | Let external agents/IDEs use Herald's tools via MCP |

---

## Sources

### Blog Series (Agentic Patterns, Jan 2026)
- [Part 1: Agent Skills](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/)
- [Part 2: AskUserQuestionTool](https://spring.io/blog/2026/01/16/spring-ai-ask-user-question-tool/)
- [Part 3: TodoWriteTool](https://spring.io/blog/2026/01/20/spring-ai-agentic-patterns-todowrite/)
- [Part 4: Subagent Orchestration](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents)
- [Part 5: A2A Protocol](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration/)

### Additional Blogs
- [Building Effective Agents (Part 1)](https://spring.io/blog/2025/01/21/spring-ai-agentic-patterns/)
- [ToolSearchToolCallAdvisor — 34-64% Token Savings](https://spring.io/blog/2025/12/11/spring-ai-tool-search-tools-tzolov/)
- [Tool Argument Augmenter](https://spring.io/blog/2025/12/23/spring-ai-tool-argument-augmenter-tzolov/)
- [Recursive Advisors](https://spring.io/blog/2025/11/04/spring-ai-recursive-advisors/)
- [Spring AI Agents & Bench](https://spring.io/blog/2025/10/28/agents-and-benchmarks/)
- [Prompt Engineering Techniques](https://spring.io/blog/2025/04/14/spring-ai-prompt-engineering-patterns/)
- [MCP Boot Starters](https://spring.io/blog/2025/09/16/spring-ai-mcp-intro-blog/)

### Spring AI 2.0 Reference
- [ChatClient API](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [Advisors API](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Recursive Advisors](https://docs.spring.io/spring-ai/reference/api/advisors-recursive.html)
- [Effective Agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
- [MCP Overview](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- [Dynamic Tool Search](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/guides/dynamic-tool-search.html)

### Community
- [spring-ai-agent-utils](https://github.com/spring-ai-community/spring-ai-agent-utils)
- [spring-ai-a2a](https://github.com/spring-ai-community/spring-ai-a2a)
- [spring-ai-examples/agentic-patterns](https://github.com/spring-projects/spring-ai-examples/tree/main/agentic-patterns)

---

*Generated March 19, 2026 from codebase analysis at commit `63f81e4` on branch `main`*
