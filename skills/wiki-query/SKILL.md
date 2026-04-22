---
name: wiki-query
description: >
  Searches Herald's long-term memory wiki (MEMORY.md + sources/concepts/entities)
  and answers questions with explicit page citations instead of from model memory.
  Use whenever the user asks "what do we have on X?", "have we saved anything
  about Y?", "what did that article about Z say?", "remind me what we know about…",
  or any recall question that should be answered from stored memory rather than
  fabricated.
---

# Wiki Query

The read-path half of Herald's compounding wiki. Pairs with `wiki-ingest` (write) and `wiki-lint` (health).

The memory root is `$HERALD_MEMORIES_DIR` (default `~/.herald/memories`). Search with `shell` + `ripgrep`/`grep`; load pages with `MemoryView`. **Never** answer these questions from training data — if it's not in memory, say so.

## When to run

- Any recall question: "what do we know about…", "have we saved…", "what did X say about Y".
- As a follow-up in ambiguous conversations: "before I answer, let me check what we've recorded on this."
- Preflight inside other skills — if about to make a decision that should be informed by prior memory, check first.

## Pipeline

### 1. Extract search terms

From the user's question, pull:

- **Primary terms** (nouns/proper nouns) — must match.
- **Type filter** (if implied): e.g. "concepts" → restrict to `concepts/**`; "who owns X" → prefer `entities/**`; "where did we read about Y" → `sources/**`.
- **Time filter** (if relative) — "recent" → pages modified in the last 30 days; "old" → pages not touched in > 90 days.

If the question is too vague to reduce to terms ("what do you remember?"), read `MEMORY.md` and surface the catalog structure instead of grepping.

### 2. Search

Run `rg` from the memories root. Prefer `rg` (ripgrep) if available; fall back to `grep -r`:

```bash
# Whole-wiki search
cd "$HERALD_MEMORIES_DIR" && rg -l -i --type md '<primary-term>' .

# Type-filtered
cd "$HERALD_MEMORIES_DIR" && rg -l -i --type md '<term>' concepts/ entities/ sources/

# Frontmatter-filtered (e.g., only concept pages)
cd "$HERALD_MEMORIES_DIR" && rg -l --multiline --multiline-dotall 'type:\s*concept[\s\S]*?<term>' .
```

**Tips:**
- Use `-i` for case-insensitive match.
- Use `-C 3` when you need context snippets for synthesis.
- Combine terms with `rg -e 'termA' -e 'termB'` (OR) or pipe searches (AND).
- Exclude noise: `--glob '!log.md' --glob '!hot.md'`.

### 3. Rank and load

Candidate set = grep-hit files + any files they wikilink to (1 hop). Rank by:

1. Term appears in the `name:` or `description:` frontmatter (strongest signal).
2. Term appears in a heading (`^#`).
3. Raw body-text match count.

Load the top 3–5 via `MemoryView`. If a candidate's `## Referenced in` points to a `sources/` page, load that too — it's usually the origin of the claim.

### 4. Synthesize with citations

Write an answer that is:

- **Grounded.** Every non-trivial claim ends with a citation to a specific page: `see [concepts/hot-path](concepts/hot-path.md)`.
- **Honest about gaps.** If two pages disagree, surface both and flag the contradiction. If nothing is found, say "no memory on this" — do NOT fill with training-data content.
- **Concise.** Lead with the direct answer, then supporting page list. Don't dump page contents.

Example shape:

> Hot-path code in this codebase is the request/response flow — `concepts/hot-path.md` defines it explicitly and distinguishes it from CPU-hot code. The convention came from a team decision referenced in `sources/team-decision-hot-path.md` (2026-02-14). Cold-path code may do expensive things; hot-path may not.
>
> **Pages cited:**
> - [concepts/hot-path](concepts/hot-path.md)
> - [sources/team-decision-hot-path](sources/team-decision-hot-path.md)

### 5. Close the loop

If during synthesis you *synthesized* something new (a claim that isn't on any single page but follows from combining two), offer to save it:

> I had to combine pages to answer that. Want me to add a new concept page `concepts/<proposed-slug>.md` summarizing the combined view? Use `wiki-ingest` to do it.

Don't auto-save — always ask first.

## Guardrails

- **Citations are required** for any factual claim the answer rests on. No citation → assume it's model fabrication and drop the claim.
- **Stale check.** Before recommending action based on a memory, spot-check the citation: does the file still exist? Does the claim still match current code/state? If no, say "this memory is from <date> and may be stale".
- **Don't recursively expand.** One hop of wikilink following is enough — deeper expansion wastes context.
- **Scope small.** If the user asks a question clearly outside memory's scope (e.g. "what's the weather?"), don't force this skill — route to the appropriate one.

## Quick recipes

**"What do I know about Dan?"**
```bash
cd "$HERALD_MEMORIES_DIR" && rg -l -i --type md 'dan\b' .
```

**"What concepts have we saved?"**
Read `MEMORY.md` and surface the `## Concepts` section. No grep needed.

**"Any sources on compaction?"**
```bash
cd "$HERALD_MEMORIES_DIR" && rg -l -i --type md 'compact' sources/
```

**"What does page X link to?"**
```bash
rg -o '\[\[[^\]]+\]\]' "$HERALD_MEMORIES_DIR/concepts/X.md"
```

## Related skills

- `wiki-ingest` — write path.
- `wiki-lint` — surfaces orphans and broken links across the wiki.
