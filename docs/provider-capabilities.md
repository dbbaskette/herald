# Providers and capabilities

Herald starts when at least one supported model provider is configured. Anthropic
is the default selection, but it is not required. If the selected provider is not
usable, Herald chooses the first configured provider in this order: Anthropic,
OpenAI, Gemini, Ollama, LM Studio. `./run.sh doctor`, startup preflight, bean
creation, `/api/capabilities`, and the console use this same resolution logic.

## Model providers

| Provider | Required environment setting | Spring property | Default main model | Base URL |
| --- | --- | --- | --- | --- |
| Anthropic | `ANTHROPIC_API_KEY` | `herald.providers.anthropic.api-key` | `HERALD_MODEL_DEFAULT` (`claude-sonnet-4-5`) | Anthropic service default |
| OpenAI | `OPENAI_API_KEY` | `herald.providers.openai.api-key` | `HERALD_MODEL_OPENAI` (`gpt-4o`) | `herald.providers.openai.base-url` (`https://api.openai.com`) |
| Gemini | `GEMINI_API_KEY` | `herald.providers.gemini.api-key` | `HERALD_MODEL_GEMINI` (`gemini-2.5-flash`) | `herald.providers.gemini.base-url` (Google's OpenAI-compatible endpoint) |
| Ollama | `OLLAMA_BASE_URL` | `herald.providers.ollama.base-url` | `HERALD_MODEL_OLLAMA` (`llama3.2`) | Required absolute HTTP(S) URL |
| LM Studio | `LMSTUDIO_BASE_URL` | `herald.providers.lmstudio.base-url` | `HERALD_MODEL_LMSTUDIO` (`qwen/qwen3.5-35b-a3b`) | Required absolute HTTP(S) URL |

### Fallback and restart

Hosted providers require a nonblank API key. Local providers require a valid
absolute HTTP(S) base URL; their placeholder API keys are optional. Base URLs
with credentials, query strings, or fragments are rejected. Set the initial
selection with `HERALD_DEFAULT_PROVIDER` or
`herald.agent.default-provider`. The console and `/model` command can switch
among providers that were configured when the bot started.

Provider state `healthy` means its local configuration passed validation. It
does not claim the remote service is reachable. Add/remove provider credentials,
change a base URL, or change a startup default, then restart the bot. With the
repository workflow, update `.env` and run `./run.sh all`.

### Property precedence

Spring Boot precedence applies: command-line properties override Java system
properties, operating-system environment, external profile configuration, and
the packaged `application.yaml`, in that order. `.env` is not read by Spring;
`run.sh` sources it into the process environment. The Settings page stores
preferences but does not override any of these sources; see [Console settings](settings.md).

## Optional runtime capabilities

| Capability | Enable/configure | Default | In task-agent mode | Disabled or missing behavior |
| --- | --- | --- | --- | --- |
| Telegram | `HERALD_TELEGRAM_BOT_TOKEN` and `HERALD_TELEGRAM_ALLOWED_CHAT_ID` | Inactive until both values exist | Disabled | No Telegram beans, polling, sender, or scheduler |
| SQLite persistence | `HERALD_PERSISTENCE_ENABLED`, `herald.memory.db-path` | Enabled with `~/.herald/herald.db` | Disabled | No data source, schema, usage persistence, cron, or MeetingNotes |
| Cron | `HERALD_CRON_ENABLED` | Enabled when persistence is usable | Disabled | No cron repository, jobs, command poller, tools, or scheduler |
| MeetingNotes | `HERALD_MEETINGNOTES_ENABLED` | Disabled | Disabled | No catalog, API, recovery, catch-up, ingestion, or probes |
| Meeting recap | `HERALD_MEETINGNOTES_RECAP_ENABLED` | Enabled | Disabled with MeetingNotes | Controls recap after ingestion; does not enable ingestion |
| Meeting reminders | `HERALD_MEETINGNOTES_REMINDERS_ENABLED` | Enabled | Disabled with MeetingNotes | Controls reminder creation after ingestion; does not enable ingestion |
| Google Workspace CLI | `HERALD_GOOGLE_ENABLED` | Disabled | Disabled | Missing or unauthenticated `gws` registers no tool and does not block startup |
| Apple Reminders CLI | `HERALD_REMINDERS_ENABLED` | Disabled | Disabled | Missing CLI registers no tool and does not block startup |
| Obsidian links | `HERALD_OBSIDIAN_VAULT_PATH`, optional `HERALD_OBSIDIAN_VAULT_MODE` | Plain Markdown/auto | Available as file configuration | Blank path skips vault integration |
| MCP client | `HERALD_MCP_CLIENT_ENABLED` plus external connection config | Disabled | Follows explicit config | Disabled clients create no connections; enabled connection failures do not abort local startup |
| A2A client | `HERALD_A2A_CLIENT_ENABLED` plus `herald.a2a.agents` | Disabled | Follows explicit config | Agent entries are ignored until the client is explicitly enabled |
| A2A server | `HERALD_A2A_SERVER_ENABLED` plus server settings | Disabled | Follows explicit config | No AgentCard or JSON-RPC server surface |

`--agents=path.md` selects task-agent mode. It hard-disables assistant persistence,
Telegram, MeetingNotes, cron, Google and Reminders discovery, and their scheduler
infrastructure even if inherited configuration contains credentials. Model
providers, local tools, file memory, explicitly configured MCP, and explicitly
configured A2A remain separate capabilities.

The bot publishes normalized state at `GET /api/capabilities`: `healthy`,
`disabled`, `unconfigured`, `unavailable`, `failed`, or `unknown`. The console
keeps these meanings distinct. Optional disabled integrations count as skipped
setup; an unprobed resource is never shown as healthy merely because its count
defaults to zero. Credentials and credential-bearing URLs are never included.

Lifecycle details and component boundaries are listed in
[Optional integration lifecycle](integration-lifecycle.md). Google authentication
is covered by [Google Workspace setup](gws-setup.md). Meeting recovery behavior
is covered by [MeetingNotes recovery](integrations/meeting-recovery.md).

## A2A client and server

Client and server switches are independent. Enabling the server does not allow
outbound delegation, and listing remote agents does not enable the client.

```yaml
herald:
  a2a:
    client:
      enabled: true
    agents:
      - name: weather-agent
        url: http://localhost:10002/weather
    server:
      enabled: false
```

When enabled, remote references are registered alongside local subagents and
use the same `Task` tool. Herald fetches each AgentCard while constructing that
tool, then sends `message/send` over JSON-RPC for an invocation. A remote request
failure is returned as a bounded task error and does not prevent a later local
or remote task from running.
