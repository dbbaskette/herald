---
name: pdf-extract
description: >
  Extract clean structured Markdown from PDFs using
  [opendataloader-pdf](https://github.com/opendataloader-project/opendataloader-pdf) —
  preserves heading hierarchy, table structure, list nesting, and reading
  order. Far better than `pdftotext` for LLM consumption. Use whenever the
  user sends a PDF to Telegram, asks "summarize this paper / invoice /
  contract", says things like "ingest this PDF", or when wiki-ingest gets
  a PDF as the source. Also handles scanned PDFs via the tool's hybrid OCR
  mode.
---

# PDF Extract

Wraps the `opendataloader-pdf` CLI. Produces Markdown that keeps the document's *shape* — headings stay headings, tables stay tables, reading order survives multi-column layouts. The opposite of `pdftotext`'s "everything goes in a soup" output.

**This is NOT a tool you call directly.** Use `shell` to drive the CLI, then optionally hand the output to `wiki-ingest` or surface it to the user.

## Step 0 — ensure the CLI is installed

**Always run this before extracting.** It's idempotent — fast no-op when already installed, helpful prompt when not.

```bash
command -v opendataloader-pdf
```

### If it's missing

Herald's Java runtime satisfies the Java 11+ requirement; the CLI ships via pip. Install path:

1. **Confirm with the user** via `askUserQuestion`:
   > To extract structured Markdown from PDFs (heading hierarchy, tables, reading order, OCR for scans), I need the `opendataloader-pdf` CLI. Run `pip install -U opendataloader-pdf`? (~15 MB; takes ~30 seconds)

2. **Pick the right pip** — try in this order:
   ```bash
   # Homebrew Python (preferred on macOS)
   if command -v pipx >/dev/null 2>&1; then
       pipx install opendataloader-pdf
   elif python3 -m pip --version >/dev/null 2>&1; then
       python3 -m pip install --user -U opendataloader-pdf
   else
       echo "Need pipx or pip3 — install Python 3.10+ first"
       exit 1
   fi
   ```

   Prefer `pipx` (isolated) over a global `pip install` so the user's Python environment stays clean. Fall back to `python3 -m pip --user` if neither pipx nor an active venv is around.

3. **Verify**:
   ```bash
   opendataloader-pdf --version
   ```

   On `command not found` after install: the install location may not be on `PATH`. For `pip install --user` on macOS that usually means `~/Library/Python/3.X/bin` — point this out to the user and tell them how to add it (`export PATH="$HOME/Library/Python/3.11/bin:$PATH"` in their shell rc), then proceed in the same shell session if possible.

4. **Report success + proceed.** Don't stop here — the user asked for a PDF to be processed; carry on with the extraction once the CLI verifies.

### If the user declines the install

Tell them their fallback options:
- `pdftotext` (`brew install poppler`) — flat text, fine for short PDFs.
- Saved file at `~/.herald/uploads/...` — the agent can still reach it via shell tools.

Do NOT silently fall back without surfacing the choice — the user might genuinely prefer the simpler tool, but they should pick.

## Inputs

- A local file path (typically under `~/.herald/uploads/` after Telegram delivers a document).
- A directory of PDFs (the CLI handles multi-file input natively).

## Output formats

The CLI supports `json`, `markdown`, `html`, `txt`, and annotated PDF. For Herald's purposes, **always pick `markdown`** — the model reasons over it directly. Combine with `json` only when you need the explicit bounding-box / type metadata for downstream structured analysis.

## Common recipes

### "Extract this PDF" — the default path

After Step 0 (verify or install the CLI):

```bash
PDF_PATH="$1"          # e.g. ~/.herald/uploads/1729800000_paper.pdf
OUT_DIR="$(dirname "$PDF_PATH")/$(basename "$PDF_PATH" .pdf).extracted"

opendataloader-pdf \
    --format markdown \
    --output "$OUT_DIR" \
    "$PDF_PATH"
```

The CLI writes `<basename>.md` into `$OUT_DIR`. Read that file and surface it to the user (or pipe it through `wiki-ingest` for filing).

### "Summarize this paper" — extract first, then summarize

1. Run the extract recipe above.
2. Read the produced Markdown.
3. Build a summary prompt: "Summarize this paper into 5 bullets covering: question, method, key findings, limitations, what to do with it."
4. If the document is huge (> 40k tokens), summarize section-by-section using the heading boundaries the extractor preserved.

### "Ingest this PDF into memory" — pdf-extract → wiki-ingest

1. Run the extract recipe.
2. Hand the resulting Markdown + the original filename + (if available) the source URL to `wiki-ingest` as the source body.
3. wiki-ingest creates `sources/<slug>.md`, finds the takeaways, and updates concepts/entities pages.

The advantage over wiki-ingest reading the raw PDF: the extracted Markdown gives the model a clean structural view, so the takeaways it pulls out are dramatically better than from a flat text dump.

### Tables-heavy documents (invoices, financial reports)

Combine markdown + json when you need to programmatically pull specific table cells:

```bash
opendataloader-pdf --format markdown,json --output "$OUT_DIR" "$PDF_PATH"
```

The JSON has bounding boxes and explicit `type: table` blocks. Only fetch the JSON when you actually need cell-level access — for narrative summarization, the Markdown is enough.

### Scanned PDFs

The tool auto-detects scanned PDFs and runs OCR via its hybrid mode. No flag needed. If results look garbled, the original is probably a low-res scan — flag this to the user rather than burning tokens trying to reason over noise.

## Output handling

- The Markdown can be **large**. A 30-page paper produces ~80–150 KB of Markdown. Don't paste the full output back to Telegram unless the user asks. Default behavior: send a 5–10 line summary, mention how many pages were extracted and where the full Markdown lives, offer to ingest into memory.
- The output directory is left in place after extraction. Cleanup is the user's call — don't auto-delete since they might want to re-read.

## Guardrails

- **Don't run on remote URLs directly.** The CLI accepts local paths only. If the user gave you a URL, fetch the PDF with the web tool first, save it locally, then extract.
- **Watch the file-size cap.** Telegram already restricts uploads, but for documents arriving via other channels (filesystem, webhook), refuse PDFs over ~25 MB unless the user explicitly opts in — extraction time scales with page count.
- **AI-safety filters.** The tool has a built-in prompt-injection detector. If it warns about the document, **mention this to the user** — don't silently swallow the warning. A PDF crafted to inject prompts is rare but real.
- **Don't ingest sensitive PDFs without confirmation.** If the file mentions "confidential", "personal", "medical", or "financial", confirm with the user before piping into `wiki-ingest`. Memory pages are world-readable on disk.

## When NOT to use this skill

- For plain `.txt`, `.json`, `.xml` — Herald's TelegramPoller already inlines these. Don't add overhead.
- For tiny single-paragraph PDFs — `pdftotext` (already wired into TelegramPoller's fallback) handles those fine and is faster to invoke.
- For images embedded in PDFs — extract the text/structure here, but if the user wants vision-style analysis on a specific figure, screenshot it and send as a photo (multimodal path, #320).

## Related

- `skills/optional-deps/SKILL.md` — install + verify recipe for `opendataloader-pdf`.
- `skills/wiki-ingest/SKILL.md` — the natural downstream consumer for "PDF → memory pages."
- `skills/wiki-query/SKILL.md` — answers questions over the resulting wiki pages.
