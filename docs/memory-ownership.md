# Learned memory and retained legacy entries

**File Memory is Herald's canonical learned-memory surface.** The configured `herald.long-term-memory.memories-dir` / `HERALD_MEMORIES_DIR` (default `~/.herald/memories`) holds `MEMORY.md`, typed Markdown notes, and the memory log. The index is supplied to the agent; it reads relevant notes with `MemoryView` and maintains them through upstream AutoMemoryTools wrapped by `HeraldAutoMemoryAdvisor` for Herald's logging and approval behavior. Keep this working advisor; adopting an obsolete upstream class name is not a migration requirement.

The console's **File Memory** tab edits those files. Typed frontmatter normally identifies `user`, `feedback`, `project`, or `reference` notes; existing concepts/entities/sources and other supported pages remain usable. The optional **Obsidian** tab searches the configured vault. If the memory directory is inside that vault, these can be views of the same Markdown files; do not create duplicate notes merely to populate both tabs.

## Legacy SQLite retention decision

Retain the existing SQLite `memory` table and `/api/memory` key/value CRUD for compatibility with older clients and manual reference. Existing rows remain in place. Herald does not register the former `memory_set`/`memory_get`/`memory_list` tools or inject those rows as a learned-memory block in its normal assistant chain. Creating an entry named `user.name`, `cron.timezone`, or `bot.mode` here does not configure runtime settings or teach the agent that fact. The **Legacy Key·Value** tab explains this distinction before edits/import/export.

Use ordinary chat requests to remember something, or edit **File Memory**, when the agent should learn it. The Telegram `/memory` command points to long-term memory files; it is not a legacy database viewer. Automated briefing prompts use `MemoryView` with the file index and relevant notes, not the removed `memory_list` tool.

No automatic conversion, row deletion, or background synchronization is performed. This is an explicit retention decision, so migration conversion/rollback requirements do not apply. A future migration must first define backup, typed conversion, idempotency, verification, and rollback, rather than treating these untyped manual strings as learned facts automatically.

## Legacy backup and import

In **Legacy Key·Value**, **Export legacy backup** first reads the complete current legacy list. If that read fails, it reports the error and offers retry without downloading an empty or stale backup. Filters do not limit the export. The downloaded `herald-legacy-memory-backup.json` is a plain JSON object mapping keys to string values, preserving compatibility with older flat-map exports, including arbitrary keys and multiline strings.

**Import legacy JSON** restores this flat-map format only into the legacy table. Matching keys are overwritten; other keys remain. Non-string values and individual failed writes are reported. This is an explicit restore/import, not an atomic database rollback: partial success is reported by count and error. Export first before replacing entries. JSON backup preserves keys and values; imported rows receive new update timestamps. For exact database recovery (including metadata and other tables), use a consistent SQLite database backup separately.

These controls do not export, restore, migrate, delete, or edit learned Markdown notes or Obsidian pages. Back up the configured memory directory and vault separately. SQLite still also stores conversation history, scheduling, and other application state in separate tables; retaining the legacy `memory` table does not change their ownership.
