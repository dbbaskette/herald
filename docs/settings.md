# Console settings and runtime configuration

The Settings page stores five **preferences** in SQLite. They do not override the
bot's environment or YAML configuration, and restarting does not import them.
This preserves the existing configuration ownership: all five runtime values are
bound when the bot starts. No setting on this page changes the running bot.

| Saved preference | Bot environment variable | YAML property |
| --- | --- | --- |
| `agent.persona` | `HERALD_AGENT_PERSONA` | `herald.agent.persona` |
| `agent.max-context-tokens` | `HERALD_AGENT_MAX_CONTEXT_TOKENS` | `herald.agent.max-context-tokens` |
| `cron.timezone` | `HERALD_CRON_TIMEZONE` | `herald.cron.timezone` |
| `obsidian.vault-path` | `HERALD_OBSIDIAN_VAULT_PATH` | `herald.obsidian.vault-path` |
| `weather.location` | `HERALD_WEATHER_LOCATION` | `herald.weather.location` |

To apply a preference, copy it to the winning configuration source shown on the
page, then restart the bot. For the repository `.env` workflow, update `.env` and
run `./run.sh all` to reload environment configuration. Explicit command-line or
other higher-priority Spring property sources can override environment/YAML;
the source shown by the bot identifies the winner. Application YAML placeholders
use the documented environment variable when present, otherwise their default.

The page shows saved preference, actual runtime effective value, and source for
each field. **Matches runtime configuration** means the values agree; it does not
mean the save applied anything. **Not applied** means update the winning source
and restart. **Unknown** means the bot snapshot could not be obtained; it is never
replaced with a SQLite value or guessed from the console process environment.
Refresh the page after restart to retrieve the new snapshot.

## API contract

`GET /api/settings` and `PUT /api/settings` return a structured result:
`success`, `savedSettings`, per-key `settings`, `validationErrors`, `error`, and
`message`. PUT continues to accept a flat object of supported keys and text values.
Only the five supported preference keys are exposed; internal operational state
and old credential entries are neither returned nor writable here.

Each setting reports `saved`, `effective` (nullable), `source`,
`environmentVariable`, `application`, and `restartRequired`. Applications are
`matches-runtime`, `configuration-managed`, or `unknown`. `restartRequired=true`
is conditional on first updating configuration; restart alone never applies a
SQLite preference. The narrow bot endpoint `GET /api/runtime/settings` snapshots
the bound `HeraldConfig` at startup. The UI queries that endpoint with a bounded
timeout and never imports bot classes or autoconfiguration.

PUT validates every field before any write. Timezones must be Java `ZoneId`s;
context limits must be whole numbers from 1 to 2,000,000. Values are limited to
4096 characters. Invalid requests return HTTP 400 with field-level errors. The
whole update and saved-value read run in one transaction; any write/read failure
rolls back every change. HTTP 500 distinguishes `save-database-failed`,
`save-failed`, and `load-failed`. A successful persistence operation remains a
success when the runtime is unavailable, with application status unknown.

The form keeps drafts on save failure and offers Retry. Concurrent submits are
blocked; edits made while a save is pending are preserved. Reset discards the
draft only when explicitly clicked.

## Google Account

Google credentials are not settings-form fields. Set
`GOOGLE_WORKSPACE_CLI_CLIENT_ID` and `GOOGLE_WORKSPACE_CLI_CLIENT_SECRET` in `.env`,
set `HERALD_GOOGLE_ENABLED=true`,
follow [Google Workspace setup](gws-setup.md), and run `./run.sh all` to reload the
environment and synchronize CLI credentials. Then use Connect Google Account.
