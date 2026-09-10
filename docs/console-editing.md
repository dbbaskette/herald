# Console scheduling, skills and memory

The cron editor uses `/api/cron` for listing, creating, editing, toggling,
deleting and requesting runs. `/api/cron-jobs` remains a compatibility alias.
Schedules retain their original five- or six-field text; Spring evaluates
five-field expressions with a zero seconds field. The bot publishes its
`herald.cron.timezone` (default `America/New_York`) to the shared database;
the console uses that timezone for next-run previews, including DST.
When the bot has never started, the console uses its matching configuration.

A successful edit is durable immediately, with a queued scheduler update.
“Run now” means queued until the bot claims the command; completed, failed and
skipped are distinct outcomes. A stopped bot leaves requests queued. Restarted
interrupted runs are marked failed with an unknown outcome, rather than replayed.
Built-in jobs cannot be deleted; their prompt edits are appended to the generated
briefing context. The command consumer assumes one bot process per database and
processes commands sequentially; long runs can delay subsequent queued commands.

## Skill validation

The editor previews the parsed `name` and `description`, adds line diagnostics,
and checks references after a short pause in typing. Invalid or missing YAML
fields block saving, including direct API saves. Reference warnings do not block
saving. Requests for older drafts cannot replace current diagnostics or edits.

`POST /api/skills/validate` accepts `{ "name": "directory-name", "content": "..." }`.
It returns `valid`, `name`, `description`, `diagnostics` (severity, code, message,
line, column), `capabilityStatus` and `vaultStatus`. Content is limited to 256 KiB.
Tool checks use the running bot's configured tool-name inventory; when the bot
is unavailable the editor says that the checks were not run. This inventory
reports registered Herald tools, not arbitrary shell commands or external CLIs.

Reference resolution uses the local or bundled skill directory, and
`herald.ui.project-path` for repository `docs/` and `examples/` references
(default: console working directory). Set this to the Herald checkout when the
console runs elsewhere. Wikilinks use `herald.obsidian.vault-path` or
`HERALD_OBSIDIAN_VAULT_PATH`; no vault means checks are off. Concrete links,
including links in ordinary code blocks, are checked. Explicit tutorial,
example, sample and template sections, generated-content commands, and placeholder
links describe future/example notes and are excluded. Vault lookup indexes up to
10,000 entries at depth 12; validation displays at most 100 reference warnings.

## File memory

The File Memory tab browses the index and typed Markdown notes, searches their
text and filters tags. Save checks the original content version and replaces
the file atomically. A conflict preserves the draft for reconciliation. Files
over 256 KiB can be found by filename but cannot be edited through the console;
no truncated original is offered for saving.

Delete requires confirmation and moves the original into
`.memory-trash/<token>/memory.md`, with an `original-path` manifest. Undo restores
it only if the original path is free. Recovery also works with
`POST /api/memory/files/restore` and `{ "token": "..." }`. Trash remains on disk
if the page closes. Absolute paths, traversal and symlink files are rejected.

“Loaded” badges mean the selected conversation actually read that note during
the executing turn, or the hot-context advisor injected it. These observations
are shared between bot and console through `.memory-context/` and clear when
the turn ends. They expire conservatively after 60 seconds without an update,
so a crashed or long-running turn cannot leave a permanent badge. Herald's
one-shot history does not retain tool-result bodies after a turn. Attribution
uses recorded mutation metadata or existing frontmatter; legacy notes without
attribution remain usable. `.memory-attribution/` records are tied to content
hashes to avoid attributing replacement text to an earlier conversation.

The legacy key/value store is retained for compatibility/manual reference; it is not learned memory or automatically injected context. Its JSON backup/import covers only legacy keys and values. See [Memory ownership and retention](memory-ownership.md). The Obsidian tab is hidden
when no vault is configured. A graph visualization remains optional and is not
included in this change.

See [draft safety and versioned writes](console-draft-safety.md) for Save / Discard / Stay navigation, external-edit reconciliation, and the required `If-Match` header on skill and prompt mutations.
