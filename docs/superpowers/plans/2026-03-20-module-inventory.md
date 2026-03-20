# Module Inventory Documentation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce `docs/module-inventory.md` categorizing every class in `herald-bot` by dependency type (core, persistence, telegram, ui).

**Architecture:** Single research-and-write task — scan all Java files, classify by imports, output structured markdown tables.

**Tech Stack:** Markdown documentation only, no code changes.

---

## File Structure

| File | Responsibility |
|------|---------------|
| Create: `docs/module-inventory.md` | Categorized inventory of all herald-bot classes with dependency analysis |

---

### Task 1: Generate Module Inventory Document

**Files:**
- Create: `docs/module-inventory.md`

- [ ] **Step 1: Scan all Java files and classify by imports**

Read every `.java` file under `herald-bot/src/main/java/com/herald/` and classify each class into one of four categories based on its imports:

- **Core** — No persistence, telegram, or UI dependencies. Pure agentic loop, stateless advisors, file-based tools.
- **Persistence** — Imports `JdbcTemplate`, `DataSource`, `ChatMemoryRepository`, `MemoryRepository`, `CronRepository`, or any `javax.sql`/`java.sql` types.
- **Telegram** — Imports `com.pengrad.telegrambot` or telegram-specific Herald classes.
- **UI** — REST controllers or classes serving the web interface.

For each class, note:
- Component type (advisor, tool, config, service, repository, etc.)
- Whether it's persistence-dependent vs stateless (for advisors and tools specifically)

- [ ] **Step 2: Write the inventory document**

Create `docs/module-inventory.md` with this structure:

```markdown
# Herald Module Inventory

Categorization of all `herald-bot` classes by dependency type, produced for the dual-mode extraction (Phase 1).

## Summary

| Category | Count | Description |
|----------|-------|-------------|
| Core | N | No persistence dependency — candidates for herald-core |
| Persistence | N | Requires JDBC/SQLite |
| Telegram | N | Requires Telegram bot library |
| UI | N | REST controllers for web interface |

## Advisors

| Class | Category | Notes |
|-------|----------|-------|
| ... | ... | ... |

## Tools

| Class | Category | Notes |
|-------|----------|-------|
| ... | ... | ... |

## Services & Components

| Class | Category | Notes |
|-------|----------|-------|
| ... | ... | ... |

## Configuration

| Class | Category | Notes |
|-------|----------|-------|
| ... | ... | ... |

## Repositories & Persistence

| Class | Category | Notes |
|-------|----------|-------|
| ... | ... | ... |
```

**Classification rule:** Classify by *functional* dependency, not just direct imports. If a class injects a persistence-dependent component (e.g., MemoryTools), classify it as persistence even if it doesn't import JdbcTemplate directly.

Key classifications to verify (from issue notes):
- `MemoryBlockAdvisor`, `OneShotMemoryAdvisor`, `ContextCompactionAdvisor` → persistence (use ChatMemory/MemoryTools which require persistence at runtime)
- `DateTimePromptAdvisor`, `ContextMdAdvisor` → core (stateless)
- `ToolPairSanitizingAdvisor`, `PromptDumpAdvisor` → core (stateless)
- `MemoryTools`, `CronTools` → persistence
- `FileSystemTools`, `WebTools` → core
- `HeraldShellDecorator` → persistence (uses JdbcTemplate for Google credentials)
- `GwsTools` → persistence (uses JdbcTemplate for Google credentials)
- `ModelSwitcher` → persistence (uses JdbcTemplate for persisting model overrides)

- [ ] **Step 3: Commit**

```bash
git add docs/module-inventory.md
git commit -m "docs: add module inventory categorizing herald-bot classes by dependency (#214)"
```
