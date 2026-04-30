---
name: markitdown
description: >
  Convert any document (PDF, DOCX, PPTX, XLSX, HTML, EPUB, CSV, JSON, XML,
  images with OCR, audio with transcription, ZIP archives, YouTube URLs)
  into clean Markdown optimized for LLM consumption. Use whenever the user
  sends a document to Telegram or the web chat, says "summarize this PDF /
  paper / invoice / contract / spreadsheet", asks for OCR or transcription,
  or when wiki-ingest receives a non-text source. Replaces the older
  pdf-extract skill — markitdown handles the same PDF cases plus 20+ other
  formats with one pipeline.
---

# MarkItDown

Wraps Microsoft's [`markitdown`](https://github.com/microsoft/markitdown) CLI / Python library. Produces Markdown that preserves structure — headings stay headings, tables stay tables, lists stay lists, hyperlinks survive. The model reasons over the result directly.

**This is NOT a tool you call directly.** Use `shell` to drive the CLI, then optionally hand the output to `wiki-ingest` or surface it to the user.

## Step 0 — ensure markitdown is installed

**Always run this before converting.** Idempotent — fast no-op when already installed.

```bash
command -v markitdown
```

### If it's missing

Install path (Python 3.10+ required; Herald's runtime already has it):

1. **Confirm with the user** via `askUserQuestion`:
   > To convert this document I need the `markitdown` CLI (Microsoft's document → Markdown converter, supports PDF / DOCX / PPTX / XLSX / images / audio / HTML / YouTube / and more). Run `pipx install 'markitdown[all]'`? (~50 MB; takes 30–60 seconds)

2. **Pick the right installer** — try in this order:
   ```bash
   if command -v pipx >/dev/null 2>&1; then
       pipx install 'markitdown[all]'
   elif python3 -m pip --version >/dev/null 2>&1; then
       python3 -m pip install --user -U 'markitdown[all]'
   else
       echo "Need pipx or pip3 — install Python 3.10+ first"
       exit 1
   fi
   ```

   Prefer `pipx` (isolated) over a global `pip install` so the user's Python environment stays clean.

3. **Verify**:
   ```bash
   markitdown --help | head -3
   ```

   On `command not found` after install: the install location may not be on `PATH`. For `pip install --user` on macOS that's usually `~/Library/Python/3.X/bin` — point this out to the user with the rc-file fix (`export PATH="$HOME/Library/Python/3.11/bin:$PATH"`), then proceed in the same shell session if possible.

4. **Modular installs** if `[all]` is too heavy or fails:
   ```bash
   pipx install 'markitdown[pdf,docx,pptx,xlsx]'   # office + pdf only
   pipx inject markitdown 'markitdown[audio]'      # add audio later
   pipx inject markitdown 'markitdown[youtube]'    # add youtube later
   ```

5. **Report success + proceed.** Don't stop here — the user asked for a document to be processed; carry on once the CLI verifies.

### If the user declines the install

Tell them their fallback options:
- `pdftotext` (`brew install poppler`) — flat text, fine for short PDFs.
- The saved file at `~/.herald/uploads/...` is still reachable via `shell` for a manual approach.

Do NOT silently fall back without surfacing the choice.

## Inputs

- A local file path (typically under `~/.herald/uploads/` after Telegram or the web chat delivers a document).
- A URL — the CLI fetches HTML, EPUB, RSS, and YouTube transcripts directly.
- A directory or ZIP archive — the CLI walks contents and concatenates output.

## Common recipes

### "Convert this PDF / document" — the default path

After Step 0:

```bash
INPUT="$1"            # e.g. ~/.herald/uploads/1729800000_paper.pdf
OUT_PATH="$(dirname "$INPUT")/$(basename "$INPUT" | sed 's/\.[^.]*$//').md"

markitdown "$INPUT" -o "$OUT_PATH"
```

Read `$OUT_PATH` back and surface a summary to the user.

### "Summarize this paper" — convert first, then summarize

1. Run the convert recipe.
2. Read the produced Markdown.
3. Build a summary prompt: "Summarize this paper into 5 bullets covering: question, method, key findings, limitations, what to do with it."
4. If the document is huge (> 40k tokens), summarize section-by-section using the heading boundaries markitdown preserved.

### "Ingest this into memory" — markitdown → wiki-ingest

1. Run the convert recipe.
2. Hand the resulting Markdown + the original filename + (if available) the source URL to `wiki-ingest` as the source body.
3. wiki-ingest creates `sources/<slug>.md`, finds the takeaways, and updates concepts/entities pages.

The advantage over wiki-ingest reading the raw document: the converted Markdown gives the model a clean structural view, so the takeaways are dramatically better than from a flat text dump.

### "Transcribe this audio"

```bash
markitdown "$INPUT" -o "${INPUT%.*}.md"
```

Same command — markitdown auto-detects audio (`.wav`, `.mp3`, `.m4a`) and runs speech recognition. Requires the `[audio]` extra at install time.

### "OCR this image"

```bash
markitdown "$INPUT" -o "${INPUT%.*}.md"
```

Auto-detects images. Output includes EXIF metadata + OCR text. For richer descriptions, see the LLM-powered branch below.

### "Pull the transcript for this YouTube video"

```bash
markitdown "https://www.youtube.com/watch?v=VIDEO_ID" -o transcript.md
```

Requires the `[youtube]` extra. No login needed — uses youtube_transcript_api under the hood.

### Tables-heavy documents (invoices, financial reports)

markitdown produces Markdown tables natively. If you need cell-level access, parse the resulting Markdown table or have the model extract specific cells. (Unlike pdf-extract's old JSON mode, markitdown is Markdown-only — accept that trade-off for the broader format coverage.)

### Batch convert a directory or ZIP

```bash
markitdown archive.zip -o combined.md   # all files concatenated

# Or loop a directory:
for f in documents/*.pdf documents/*.docx; do
    markitdown "$f" -o "markdown/$(basename "$f" | sed 's/\.[^.]*$//').md"
done
```

### Enhanced PDF tables — Azure Document Intelligence (optional)

For complex multi-column or table-heavy PDFs, markitdown can offload to Azure Document Intelligence:

```python
from markitdown import MarkItDown
md = MarkItDown(docintel_endpoint="<endpoint>", docintel_key="<key>")
result = md.convert("complex.pdf")
```

Only worth wiring up when the user asks specifically about a finance / legal / scientific document where the default extraction is missing tables. Most personal-document use cases don't need it.

### LLM-powered image descriptions (optional)

When converting a PPTX / DOCX with embedded images, markitdown can call an LLM to describe each image:

```python
from markitdown import MarkItDown
from openai import OpenAI
client = OpenAI()
md = MarkItDown(llm_client=client, llm_model="gpt-4o")
result = md.convert("presentation.pptx")
```

Useful for picture-heavy decks. Costs API tokens — confirm with the user before enabling.

## Output handling

- Markdown can be **large**. A 30-page paper produces ~80–150 KB of Markdown. Don't paste the full output back to Telegram unless the user asks. Default behavior: send a 5–10 line summary, mention page count + where the full Markdown lives, offer to ingest into memory.
- The output file is left in place. Cleanup is the user's call — don't auto-delete since they may want to re-read.

## Guardrails

- **Local paths only for files; URLs only for web sources.** Don't pass arbitrary remote file URLs as the input — fetch them with the web tool first, save locally, then convert. (URL inputs are reserved for HTML / YouTube / RSS.)
- **File-size cap.** Refuse documents over ~25 MB unless the user explicitly opts in — conversion time scales with page count and audio length.
- **Plugin system off by default.** markitdown has an extensible plugin system (`enable_plugins=True`) that we leave disabled — only re-enable if the user explicitly asks.
- **Don't ingest sensitive documents without confirmation.** If the file mentions "confidential", "personal", "medical", or "financial", confirm with the user before piping into `wiki-ingest`. Memory pages are world-readable on disk.

## When NOT to use this skill

- For plain `.txt`, `.json`, `.xml` files that already render fine — Herald's TelegramPoller inlines small text bodies. Don't add overhead unless the user specifically asks for a Markdown reformat.
- For images where the user wants visual reasoning (not OCR) — send as a photo via the multimodal vision path; vision models read the pixels directly.

## Related

- `skills/optional-deps/SKILL.md` — install + verify recipes for optional dependencies in general.
- `skills/wiki-ingest/SKILL.md` — the natural downstream consumer for "document → memory pages."
- `skills/wiki-query/SKILL.md` — answers questions over the resulting wiki pages.
- `skills/voice-handling/SKILL.md` — same self-install pattern for the `whisper` CLI; markitdown's `[audio]` extra is an alternative for short clips.
