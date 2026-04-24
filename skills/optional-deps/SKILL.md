---
name: optional-deps
description: >
  Detect and install optional command-line tools that unlock Herald features —
  whisper (voice transcription), pdftotext (PDF extraction), gws (Gmail +
  Calendar), reminders-cli (Apple Reminders), ffmpeg (audio conversion), jq
  (JSON processing). Use whenever a fallback message mentions a missing CLI,
  whenever the user says things like "install what's missing", "what do I
  need", "can Herald do X yet", or whenever you hit a "not available" error
  that traces to a missing brew package. Also use proactively at the end of
  `herald onboard` (#306) or when `herald doctor` (#309) flags missing tools.
---

# Optional Dependencies

Herald has a handful of optional CLIs that unlock extra capabilities. The core works without any of them; they're strictly additive. This skill is the agent's knowledge of **which tool unlocks which feature** and **how to install each one**.

**This is NOT a tool you call directly.** Use `shell` + `askUserQuestion` to drive detection, confirmation, and installation.

## Catalog

| CLI | Install command | Verify | Unlocks |
|---|---|---|---|
| `whisper` | `brew install whisper-cpp` | `whisper --help` | Voice-memo transcription in Telegram ([#320](https://github.com/dbbaskette/herald/pull/324)). Without it: voice messages are saved to `~/.herald/uploads/` and the agent can only reach them via shell tools. |
| `pdftotext` | `brew install poppler` | `pdftotext -v` | Inline text extraction from PDFs sent to Telegram. Without it: PDFs are saved to `~/.herald/uploads/` and the agent can only reach them via shell tools. |
| `gws` | `brew install googleworkspace-cli` (then `gws auth login -s gmail,calendar,drive`) | `gws auth status` | Gmail + Google Calendar + Google Drive skills. Without it: those skills return "gws not configured" and fail gracefully. |
| `reminders` | `brew install keith/formulae/reminders-cli` (then grant Reminders access in System Settings → Privacy) | `reminders show-lists` | Apple Reminders read/write ([#300](https://github.com/dbbaskette/herald/issues/300)). Without it: `reminders_*` tools report "not available". |
| `obsidian` | Install the Obsidian desktop app 1.12+ and enable CLI in Settings → General | `obsidian vault` | Obsidian vault search via the `obsidian` skill. Without it: vault queries fail. |
| `ffmpeg` | `brew install ffmpeg` | `ffmpeg -version` | Voice codec conversion fallback. Some iOS Telegram voice memos ship in codecs `whisper` can't read directly; `ffmpeg` bridges the gap. |
| `jq` | `brew install jq` | `jq --version` | Clean JSON parsing in shell recipes. Multiple skills assume it's on `PATH`. |

If a feature the user wants depends on something NOT in this catalog, don't guess — tell the user to file an issue so we can add it properly.

## Core recipes

### "What do I need / what's missing?"

Walk the catalog, run `command -v <name>` for each, produce a compact report grouped by installed vs missing:

```bash
for cli in whisper pdftotext gws reminders obsidian ffmpeg jq; do
    if command -v "$cli" >/dev/null 2>&1; then
        echo "✓ $cli  ($(command -v "$cli"))"
    else
        echo "✗ $cli"
    fi
done
```

Then render as two groups. Only mention the "Unlocks" for the ones that are **missing** — the point is to answer "what am I leaving on the table?"

### "Install X" or "install what's missing"

For each target CLI:

1. **Detect**: `command -v $cli`. Skip if already installed (verify with the "Verify" column to confirm it's working).
2. **Confirm before installing**. Use `askUserQuestion`:
   > I'd like to run `brew install whisper-cpp` (~15 MB). This unlocks voice-memo transcription in Telegram. Proceed?
3. **Install**: run the catalog's `Install command`.
4. **Verify**: run the `Verify` command; report success/failure concretely.
5. **Report unlocked features**. Be specific: "Voice memos will now be transcribed locally on the next message."
6. **Post-install steps** (where applicable):
   - `gws` needs `gws auth login -s gmail,calendar,drive` before the skills work.
   - `reminders` needs Reminders access granted in System Settings → Privacy & Security → Reminders (prompted on first invocation).

### Fallback-message trigger

Herald's fallback prose is designed to name the missing CLI so you can react:

```
[Voice message received — install whisper (`brew install whisper-cpp`)
 for automatic transcription; file saved to ~/.herald/uploads/voice_X.ogg]
```

When you see a message like this, offer to install the named CLI right then — don't wait for the user to ask.

## Guardrails

- **Always confirm before installing.** `brew install` runs unattended; the user should explicitly authorize each one.
- **Never run `sudo`** from this skill. Homebrew doesn't need it; if a recipe seems to, something's wrong — stop and tell the user.
- **macOS only.** Most of these are macOS-specific (`reminders`, `obsidian` app, launchd services). On Linux, report the macOS-specific CLIs as "not applicable" and suggest the closest Linux equivalent only if the user asks.
- **Don't batch-install without asking.** If 5 CLIs are missing and the user says "install everything missing," confirm the full list + total download size first.
- **Respect Homebrew's own prompts.** Some installs ask for keg-only linking or add caveats; surface those to the user verbatim.

## Not in scope

- Installing Claude Code CLI, Ollama, or other LLM runtimes. Those belong in `herald onboard` (#306), not here.
- Installing Node/Python/Java runtimes. Those are prerequisites the user handles outside Herald.
- Removing tools — use `brew uninstall` directly if needed.

## Related

- `herald doctor` (#309) — runs a quick health check that includes CLI detection; this skill is the follow-up that installs the gaps.
- `herald onboard` (#306) — first-run wizard that can invoke this skill at its end for the "what else do you want working?" step.
