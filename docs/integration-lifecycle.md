# Optional integration lifecycle

Startup configuration controls these capabilities. Restart the bot after changing a flag. Persistence and cron retain the default assistant baseline; MeetingNotes, Google Workspace, and Reminders are opt-in.

| Capability | Spring property | Environment variable | Disabled behavior |
| --- | --- | --- | --- |
| SQLite persistence | `herald.persistence.enabled` | `HERALD_PERSISTENCE_ENABLED` | No data source, schema initialization, usage persistence, budget policy, cron, or MeetingNotes ingestion |
| Cron | `herald.cron.enabled` | `HERALD_CRON_ENABLED` | No repository, workers, command polling, scheduled jobs, or cron tools |
| MeetingNotes | `herald.meetingnotes.enabled` | `HERALD_MEETINGNOTES_ENABLED` | No catalog, note verifier, durable queue worker, recovery/catch-up schedule, or meeting API |
| Google CLI | `herald.google.enabled` | `HERALD_GOOGLE_ENABLED` | No CLI probe or Google tool registration |
| Reminders CLI | `herald.reminders.enabled` | `HERALD_REMINDERS_ENABLED` | No CLI probe or Reminders tool registration |

Persistence and cron default to `true`. MeetingNotes, Google Workspace, and Reminders default to `false`; set their enable flag explicitly when configuring them. An explicitly blank flag is disabled. Persistence also requires a nonblank `herald.memory.db-path`. SQLite is created only by Herald's explicit data source configuration, so turning it off does not fall back to another automatic database. Telegram can remain active with persistence or cron disabled; commands report those disabled capabilities rather than failing.

`--agents=path.md` selects ephemeral task mode. Assistant persistence, Telegram, MeetingNotes, cron, Google/Reminders discovery, and assistant scheduler infrastructure stay off even when an assistant configuration file contains enabled flags or credentials. Task execution and file memory do not require SQLite.

MeetingNotes recap and Reminders delivery remain independent: `herald.meetingnotes.recap-enabled` and `herald.meetingnotes.reminders-enabled` apply only after ingestion is enabled. Disabling all MeetingNotes ingestion pauses durable pending work without deleting it; re-enabling resumes recovery. Ingestion requires persistence for recoverable claims.

An enabled Google integration probes `gws --version` once at startup. A missing or failed CLI yields no `GwsTools` bean, active tool name, or model callback. CLI detection does not prove Google authorization: authenticate with the CLI's supported configuration as described in [Google Workspace setup](gws-setup.md). Authentication errors are reported by the actual operation.
