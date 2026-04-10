# Herald Module Inventory

Categorization of all `herald-bot` classes by dependency type for the dual-mode extraction (Phase 1).

## Summary

| Category | Count | Description |
|----------|-------|-------------|
| Core | 17 | No persistence dep — candidates for herald-core |
| Persistence | 21 | Requires JDBC/SQLite/ChatMemory |
| Telegram | 8 | Requires Telegram bot library |
| UI | 2 | REST controllers for web interface |

## Advisors

| Class | Package | Category | Notes |
|-------|---------|----------|-------|
| DateTimePromptAdvisor | com.herald.agent | Core | Stateless; resolves datetime placeholders in system prompt |
| ContextMdAdvisor | com.herald.agent | Core | File-based; reads CONTEXT.md from disk each turn |
| ToolPairSanitizingAdvisor | com.herald.agent | Core | Stateless; sanitizes orphaned tool pairs in history |
| PromptDumpAdvisor | com.herald.agent | Core | File-based diagnostic dump; no DB dependency |
| MemoryBlockAdvisor | com.herald.agent | Persistence | Injects persistent memory block; depends on MemoryTools |
| OneShotMemoryAdvisor | com.herald.agent | Persistence | Single-load/save per turn; depends on ChatMemory |
| ContextCompactionAdvisor | com.herald.agent | Persistence | Compacts history into memory via MemoryTools; depends on ChatMemory + MemoryTools |

## Tools

| Class | Package | Category | Notes |
|-------|---------|----------|-------|
| FileSystemTools | com.herald.tools | Core | Reads/writes/lists files; no DB dependency |
| WebTools | com.herald.tools | Core | HTTP fetch and Brave Search; no DB dependency |
| ShellCommandExecutor | com.herald.tools | Core | Functional interface for shell execution; no DB dependency |
| GwsAvailabilityChecker | com.herald.tools | Core | Checks gws CLI presence via process call; no DB dependency |
| ShellSecurityConfig | com.herald.tools | Core | Configuration properties for shell blocklist; no DB dependency |
| ReloadableSkillsTool | com.herald.agent | Core | Hot-reloadable file-based skills; no DB dependency |
| MemoryTools | com.herald.memory | Persistence | CRUD over SQLite memory table via MemoryRepository |
| CronTools | com.herald.cron | Persistence | Wraps CronService (JdbcTemplate-backed) for CRUD on cron jobs |
| HeraldShellDecorator | com.herald.tools | Persistence | Shell execution with JdbcTemplate injection for GWS credentials |
| GwsTools | com.herald.tools | Persistence | Gmail/Calendar CLI wrapper; uses JdbcTemplate to read GWS credentials |
| TelegramSendTool | com.herald.tools | Telegram | Sends messages via optional TelegramSender injection |

## Services & Components

| Class | Package | Category | Notes |
|-------|---------|----------|-------|
| SkillsWatcher | com.herald.agent | Core | File watcher for skills hot-reload; no DB dependency |
| AgentService | com.herald.agent | Persistence | Thin wrapper over ModelSwitcher (JdbcTemplate-backed) + AgentMetrics (JdbcTemplate) |
| BriefingJob | com.herald.cron | Persistence | Morning/weekly prompt builder; depends on MemoryRepository |
| CronService | com.herald.cron | Persistence | Schedules and executes cron jobs; depends on CronRepository (JdbcTemplate) + ChatMemory |
| ChatArchivalJob | com.herald.memory | Persistence | Archives chat sessions to Obsidian; queries SPRING_AI_CHAT_MEMORY via JdbcTemplate |
| MemoryMigrationJob | com.herald.memory | Persistence | Migrates oversized hot-memory entries to Obsidian; depends on MemoryRepository |
| AgentMetrics | com.herald.agent | Persistence | Records turn metrics to model_usage table via JdbcTemplate |
| UsageTracker | com.herald.agent | Persistence | Queries model_usage table for daily summaries via JdbcTemplate |
| ModelSwitcher | com.herald.agent | Persistence | Runtime model switching; persists overrides to model_overrides table via JdbcTemplate |
| CommandHandler | com.herald.telegram | Telegram | Handles Telegram slash commands; depends on TelegramSender + MemoryTools + ChatMemory |
| MessageFormatter | com.herald.telegram | Telegram | Splits and escapes text for Telegram's message length/format limits |
| TelegramPoller | com.herald.telegram | Telegram | Polls Telegram for updates; depends on TelegramBot (pengrad) |
| TelegramSender | com.herald.telegram | Telegram | Sends messages via TelegramBot (pengrad) |
| TelegramQuestionHandler | com.herald.telegram | Telegram | Bridges agent questions to Telegram inline keyboard; depends on TelegramSender |
| HeraldApplication | com.herald | Core | Spring Boot entry point; no functional dependencies |

## Configuration & Infrastructure

| Class | Package | Category | Notes |
|-------|---------|----------|-------|
| HeraldConfig | com.herald.config | Core | Configuration properties record; no DB dependency |
| ModelProviderConfig | com.herald.agent | Core | Creates ChatModel beans for OpenAI/Ollama/Gemini; no DB dependency |
| HeraldAgentConfig | com.herald.agent | Persistence | Main wiring config; creates ChatMemory, ModelSwitcher, all advisors; imports JdbcTemplate |
| TelegramBotConfig | com.herald.telegram | Telegram | Creates TelegramBot bean (pengrad); conditional on bot-token |
| DataSourceConfig | com.herald.config | Persistence | Creates DataSource (SQLite), JdbcTemplate, ChatMemoryRepository |
| JsonChatMemoryRepository | com.herald.config | Persistence | Custom ChatMemoryRepository storing messages as JSON blobs via JdbcTemplate |

## Repositories & Data Access

| Class | Package | Category | Notes |
|-------|---------|----------|-------|
| MemoryRepository | com.herald.memory | Persistence | JdbcTemplate CRUD for memory key-value store |
| CronRepository | com.herald.cron | Persistence | JdbcTemplate CRUD for cron_jobs table |
| CronJob | com.herald.cron | Persistence | Record type for cron job data; part of persistence layer |

## Subagent Infrastructure

| Class | Package | Category | Notes |
|-------|---------|----------|-------|
| HeraldSubagentReferences | com.herald.agent.subagent | Core | Loads subagent definitions from markdown files; no DB dependency |

## UI Controllers

| Class | Package | Category | Notes |
|-------|---------|----------|-------|
| ChatController | com.herald.api | UI | REST endpoint for web-based chat interface; POST /api/chat; delegates to AgentService |
| ModelController | com.herald.api | UI | REST endpoint for runtime model switching; GET/POST /api/model; delegates to ModelSwitcher |
