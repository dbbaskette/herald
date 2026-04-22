---
name: wiki-ingest
description: >
  Ingests a local file, URL, or pasted text into Herald's long-term memory as a
  compounding wiki — creates a `sources/` page with takeaways and creates or
  updates the related `concepts/` and `entities/` pages. Use whenever the user
  says "ingest this", "remember this article", "add this doc to memory",
  "save this URL", or hands over a file/link they want the knowledge from —
  even without the exact word "ingest".
---

# Wiki Ingest

Turns a one-off source (article, gist, PDF text, meeting notes, paper abstract) into **structured memory** the agent can reason over later. This is the write-path half of Herald's wiki: paired with `wiki-query` for the read-path.

The memory root is `$HERALD_MEMORIES_DIR` (default `~/.herald/memories`). All Markdown memory tools (`MemoryView`, `MemoryCreate`, `MemoryStrReplace`, `MemoryInsert`) operate on paths relative to that root.

### Link style (vault mode)

Before creating cross-links, **check the `## Memory Storage Mode` section in `CONTEXT.md`**:

- Mode `plain-markdown` (default) → use `[text](path.md)` for every link. `[[wikilinks]]` render as raw text in GitHub, VS Code, and most non-Obsidian viewers.
- Mode `obsidian-vault` → prefer `[[path]]` wikilinks for cross-links between pages (`[[concepts/hot-path]]`, `[[entities/acme]]`). This enables Obsidian's Graph view and backlinks. **`MEMORY.md` entries still use markdown-link syntax** because they carry explicit display titles.

When in doubt (CONTEXT.md missing the section), default to plain markdown.

## When to run

- User hands over a URL, file path, or pasted content and wants it "saved", "remembered", or "ingested".
- User reviews a document together with Herald and asks to "keep this".
- An agent task returns research output that is likely to be referenced again (always offer to ingest it).

**Do NOT** use this skill for ephemeral values (a phone number, a one-line fact, a pasted error). Use a direct `MemoryCreate` with the right type instead — ingest is for sources worth a full takeaways page.

## Inputs

Accept any of these as a source:

| Input | Handling |
|---|---|
| Local file path (`.md`, `.txt`, `.pdf`, `.docx`) | Read with the appropriate tool. For PDF/DOCX, fall back to `shell` + `pdftotext` / `pandoc` if no direct tool. |
| URL (http/https) | Fetch with the web tool. If it fails or returns bot-block HTML, tell the user; do NOT silently invent content. |
| Pasted text in the conversation | Use the text directly, ask for a title and source attribution if unclear. |

If the user hands over **more than one** source in the same turn, dispatch one subagent per source via `TaskTool` (`subagent_type: research`) and let them run in parallel. Collect their results and merge the updates to `MEMORY.md` in the main turn.

## Pipeline

For each source, the steps are fixed:

### 1. Read the source

- Record byte count and a stable identifier (URL, absolute path, or a short hash of the pasted text).
- If the content is > ~40k tokens, summarize in chunks with the summary model rather than holding the full text in context.

### 2. Extract structured metadata

Ask yourself these questions explicitly before writing anything:

- **Title** — what's the canonical name? (URL `<title>`, file basename, or author-supplied.)
- **Author / origin** — who produced it? One-line attribution.
- **Key concepts** — 2–6 ideas or terms this source explains that would be worth having their own page. Favor durable ideas over specific events.
- **Named entities** — people, teams, companies, products mentioned prominently.
- **Takeaways** — 3–7 bullets of what this source actually claims. Distinct from a summary: these are the things we might cite later.
- **Applied here** — if the source is informing work already underway in this repo, name the file/PR/feature.

### 3. Create the source page

```bash
SLUG=$(echo "<source-title>" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/-/g' | sed 's/-\+/-/g; s/^-//; s/-$//')
```

Then `MemoryCreate` at `sources/$SLUG.md`:

```markdown
---
name: <source-title>
description: <one-line hook — 150 char max>
type: source
url: <origin url or file path>
ingested: <YYYY-MM-DD>
---

# <Source Title>

**Origin:** <url or path>
**Author:** <who>
**Ingested:** <date>

## Takeaways
- <bullet 1>
- <bullet 2>
- ...

## Applied here
<Where in Herald this idea has landed or is being considered. Omit section if not applicable.>

## Related concepts
- [[concepts/<slug>]]
- [[entities/<slug>]]
```

**In plain-markdown mode** swap the `Related concepts` bullets for:

```markdown
## Related concepts
- [<concept-name>](concepts/<slug>.md)
- [<entity-name>](entities/<slug>.md)
```

### 4. Create or update concept pages

For each key concept:

1. `MemoryView concepts/` to see existing pages.
2. If a matching `concepts/<slug>.md` exists, `MemoryStrReplace` to append a line under a `## Referenced in` section:
   ```
   - [[sources/<source-slug>]]  <!-- or [(source title)](sources/<source-slug>.md) in plain-markdown mode --> — <one-line why this source matters to this concept>
   ```
   Create that section with `MemoryInsert` if it doesn't exist yet.
3. If no page exists, `MemoryCreate` `concepts/<slug>.md`:
   ```markdown
   ---
   name: <concept name>
   description: <one-sentence definition>
   type: concept
   ---

   # <Concept Name>

   <One-paragraph definition in your own words. Do NOT just quote the source.>

   ## Why this matters
   <What decisions or designs this concept shapes.>

   ## Referenced in
   - [[sources/<source-slug>]]  <!-- or [(source title)](sources/<source-slug>.md) in plain-markdown mode --> — <why>
   ```

### 5. Create or update entity pages

Same two-step pattern as concepts, under `entities/<slug>.md`:

```markdown
---
name: <entity name>
description: <role + one-line hook>
type: entity
---

# <Entity Name>

**Role:** <what they do / what they are>
**Context:** <where they fit in the user's world>

## Referenced in
- [[sources/<source-slug>]] — <why>
```

### 6. Update the `MEMORY.md` index

For each new page you created (NOT for updates to existing pages), `MemoryInsert` a pointer under the matching `## Type` section:

```
- [<Title>](<path/to/page.md>) — <one-line hook, < 150 chars>
```

If the correct section doesn't exist in `MEMORY.md` yet, `MemoryStrReplace` to add it.

### 7. Append to `log.md`

The `log.md` file is auto-updated for every memory mutation by the advisor layer — you do NOT append to it directly. Just confirm that at least one `CREATE` or `STRREPLACE` line landed (tail the file to verify if in doubt).

### 8. Summarize what changed

Tell the user in 3–5 lines:

- **Source:** `sources/<slug>.md`
- **Concepts:** new (N), updated (M)
- **Entities:** new (N), updated (M)
- **Index:** <sections touched>

If you found material that didn't fit any of the three types (neither source, concept, nor entity), say so — don't try to force it.

## Guardrails

- **Never fabricate.** If a fetch fails or returns boilerplate, say so. An empty ingest with an honest log is better than a confident wrong page.
- **Don't re-ingest.** Before step 3, grep `$HERALD_MEMORIES_DIR/sources/` for the URL or source hash. If already present, tell the user and ask whether to re-extract (URL may have changed) or skip.
- **Slug safety.** Slugs must be lowercase, `[a-z0-9-]` only. Path-escape anything the user supplied.
- **Concepts are durable.** Don't create a concept page for a one-off term. If in doubt, leave it out and surface the term as a takeaway on the source page.
- **Max one open question per turn.** If you need the user to pick a title or disambiguate a concept, batch the questions.

## Batch mode (parallel)

When handed N > 1 sources in one turn:

1. In the main turn, write a short plan: list the sources, their target slugs, and any naming conflicts you need the user to resolve.
2. Dispatch one `TaskTool` subagent per source with a prompt like:
   > Ingest this source into Herald's memory following `skills/wiki-ingest`.
   > Source: `<url-or-path>`
   > Assigned source slug: `<slug>`
   > Report back: paths you created/updated and any concepts/entities you promoted.
3. Back in the main turn, merge the per-source reports, reconcile any concept pages that two subagents both wanted to create (pick one canonical slug, rewrite the other subagent's links), and write the final `MEMORY.md` updates.

## Related skills

- `wiki-query` — the read path over the same files.
- `wiki-lint` — periodic health check for orphans and dead links.
