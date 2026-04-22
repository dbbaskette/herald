---
name: wiki-lint
description: >
  Audits Herald's long-term memory wiki for orphaned pages, dead wikilinks,
  stale or contradicting claims, and index/file mismatches. Emits a report;
  does NOT mutate files without the user's explicit confirmation. Use when the
  user says "lint memory", "check the wiki", "find broken links", "audit my
  notes", or runs a scheduled memory health check via cron.
---

# Wiki Lint

The health-check arm of Herald's wiki — complements `wiki-ingest` (write) and `wiki-query` (read). This skill finds problems; it does NOT fix them silently. Every proposed fix waits for user confirmation.

Memory root: `$HERALD_MEMORIES_DIR` (default `~/.herald/memories`). All checks operate via `shell` (grep/find/awk) plus `MemoryView` for content reads.

The dead-wikilink and orphan checks already handle both link styles (`[text](path.md)` and `[[path]]`) — no vault-mode branching needed. See the `## Memory Storage Mode` section in `CONTEXT.md` only when the lint report suggests a fix that involves adding new links: match the existing mode for new links you propose.

## When to run

- User says "lint memory", "audit the wiki", "check memory health", "find orphans", "any broken links".
- After a large `wiki-ingest` batch — verify the index is consistent.
- Scheduled via cron (see "Running on a cron" below) — daily or weekly is fine.

## Checks

### 1. Orphan pages

An orphan = a `.md` file that:
- is NOT `MEMORY.md`, `log.md`, `hot.md`, or `CONTEXT.md`, AND
- does NOT have a pointer line in `MEMORY.md`, AND
- is NOT linked from any other memory page (via `[text](path.md)` or `[[path]]`).

```bash
cd "$HERALD_MEMORIES_DIR"

# All memory pages
find . -name '*.md' -type f \
  ! -name MEMORY.md ! -name log.md ! -name hot.md ! -name CONTEXT.md \
  | sed 's|^\./||' | sort -u > /tmp/wiki-all.txt

# All pages referenced from MEMORY.md or any other page
{
  rg -o '\]\(([^)]+\.md)\)' -r '$1' .
  rg -o '\[\[([^\]]+)\]\]' -r '$1' . | sed 's|$|.md|'
} | sort -u > /tmp/wiki-linked.txt

comm -23 /tmp/wiki-all.txt /tmp/wiki-linked.txt > /tmp/wiki-orphans.txt
```

Report each orphan with its `name:` frontmatter so the user can decide whether to re-link, delete, or ignore (some pages may be intentionally archived).

### 2. Dead wikilinks

A dead link = a `[text](path.md)` or `[[path]]` whose target file does not exist.

```bash
cd "$HERALD_MEMORIES_DIR"

# Markdown-style links
rg -on '\]\(([^)]+\.md)\)' -r '$1' --no-heading \
  | while IFS=: read -r src _line target; do
      [ -f "$target" ] || printf '%s -> %s (missing)\n' "$src" "$target"
    done

# Wikilink-style
rg -on '\[\[([^\]]+)\]\]' -r '$1' --no-heading \
  | while IFS=: read -r src _line target; do
      [ -f "$target.md" ] || [ -f "$target" ] \
        || printf '%s -> %s (missing)\n' "$src" "$target"
    done
```

Report each dead link with the source file, the line number, and the missing target.

### 3. Index / file mismatches

`MEMORY.md` is the catalog. Two failure modes:

- **In the index, not on disk.** A pointer in `MEMORY.md` whose target file doesn't exist.
  ```bash
  rg -o '\]\(([^)]+\.md)\)' -r '$1' MEMORY.md \
    | while read -r target; do
        [ -f "$target" ] || echo "MEMORY.md points to missing: $target"
      done
  ```
- **On disk, not in the index.** Same file set as "orphans" above — a page with no `MEMORY.md` line (orphan detection covers this).

Also check that every `MEMORY.md` entry sits under a `## <Type>` heading that matches the target page's `type:` frontmatter. A `concepts/…` pointer under `## Entities` is a misplacement.

### 4. Stale-claim / contradiction scan (LLM-assisted)

This check is optional and runs the summary model over page contents to surface claims that are likely outdated or contradicted by another page. Keep the scope narrow to avoid blowing context:

1. Pick a focus — either the user's hint ("check the auth pages", "look at my 2025 notes") or default to the 20 oldest-modified pages.
2. Load them with `MemoryView`.
3. For each, ask the model to flag:
   - **Stale markers:** absolute dates more than 6 months old, references to versions/people/URLs that may have moved on, "currently" phrasing from a fixed point in time.
   - **Contradictions:** two pages asserting different facts about the same named thing.
4. Emit findings as **suspicions**, not assertions: "`concepts/auth.md` says X. `sources/auth-rewrite-2026-02.md` says Y. Worth reconciling?"

Skip this check when running on a cron unless explicitly requested — it burns tokens and produces false positives.

## Report format

Emit a single Markdown report. Structure:

```markdown
# Wiki Lint — <ISO-8601 timestamp>

**Scope:** <full wiki | concepts/ | entities/ | ...>
**Pages scanned:** <N>

## Orphans (<count>)
- `<path>` — `<description from frontmatter>`

## Dead wikilinks (<count>)
- `<source-path>:<line>` → `<missing-target>`

## Index mismatches (<count>)
- `MEMORY.md` points to missing: `<path>`
- Misplaced: `<path>` (type: `<actual>`) is under `## <wrong-section>`

## Possible stale / contradicting claims (<count>)
- `<path>` — <one-line suspicion> (suggest review against `<other-path>`)

## Suggested next actions
1. …
2. …
```

Print the report and stop. Do not mutate the filesystem.

## Fixes (only with explicit confirmation)

If the user asks to fix something specific — "delete those orphans", "remove the dead links from concepts/hot-path.md" — then:

- For orphan pages, `MemoryDelete` them only if the user names each path or says "delete all orphans".
- For dead links, `MemoryStrReplace` each broken line on the containing page.
- For index mismatches, `MemoryStrReplace` the line to move it under the correct section or update the path.

Never batch-fix on a hunch; confirm each category before acting.

## Running on a cron

Herald's `CronService` can schedule any user prompt. Add a cron entry that invokes this skill via a natural-language prompt:

```
0 7 * * 1   # Monday 07:00
Run the wiki-lint skill over concepts/ and entities/. Skip the stale-claim scan.
DM me the report.
```

Cron-invoked lint should default to **report only** — no fixes. The user reviews in chat and asks for fixes interactively.

## Guardrails

- **Never silently delete.** All destructive ops require a named-path confirmation in the current turn.
- **Don't lint `log.md` or `hot.md`.** They are append-only / machine-managed.
- **Don't follow external URLs.** This skill only operates on local Markdown and internal links. External link-checking is a separate job.
- **Time-box the stale-claim scan.** If the scan takes more than ~2 minutes or more than 20 pages, stop and tell the user — they probably want a narrower scope.

## Related skills

- `wiki-ingest` — the write path that produces the files this skill audits.
- `wiki-query` — the read path; use it when investigating whether a flagged claim is actually stale.
