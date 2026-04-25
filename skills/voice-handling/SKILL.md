---
name: voice-handling
description: >
  Transcribe voice memos, dictation, and short audio clips to text using
  local `whisper` (openai-whisper or whisper.cpp). Converts foreign codecs
  via `ffmpeg` when needed. Use whenever the user sends a voice message to
  Telegram, asks "transcribe this audio", hands over a `.ogg` / `.m4a` /
  `.wav` / `.mp3` file, or when a fallback message mentions audio saved to
  `~/.herald/uploads/`. Keeps everything local — no audio leaves the Mac.
---

# Voice Handling

Drives a local speech-to-text pipeline built on `whisper`. Works entirely on-device; privacy-preserving by default. The `TelegramPoller` already downloads voice memos to `~/.herald/uploads/` — this skill turns those files into text the agent can reason over.

**This is NOT a tool you call directly.** Use `shell` + `askUserQuestion` to drive install, conversion, and transcription.

## Step 0 — ensure whisper is installed

**Always run this before transcribing.** Idempotent — fast no-op when present.

```bash
command -v whisper
```

### If whisper is missing

Two install paths; pick based on what the user already has. Confirm via `askUserQuestion` before either:

> To transcribe voice memos locally, I need `whisper`. Two options: 
> 1. **openai-whisper** (Python, better accuracy, ~1 GB with model download) via `pipx install openai-whisper`.
> 2. **whisper.cpp** (C++, fastest, smaller) via `brew install whisper-cpp`.
>
> Which do you prefer? (openai-whisper is the default if unsure.)

#### openai-whisper install path

```bash
if command -v pipx >/dev/null 2>&1; then
    pipx install openai-whisper
elif python3 -m pip --version >/dev/null 2>&1; then
    python3 -m pip install --user -U openai-whisper
else
    echo "Need pipx or pip3 — install Python 3.10+ first"
    exit 1
fi
whisper --help | head -5
```

The first run auto-downloads the `tiny` model (~75 MB) — normal, don't panic. Subsequent runs reuse it.

#### whisper.cpp install path

```bash
brew install whisper-cpp
whisper --version
```

Note that whisper.cpp's CLI is sometimes named `whisper-cli`. If `command -v whisper` still fails after install, check for `whisper-cli` and use that.

### Also check for ffmpeg (codec fallback)

```bash
command -v ffmpeg
```

iOS Telegram voice memos ship as Opus-in-OGG; whisper.cpp handles that fine but openai-whisper via some install paths needs `ffmpeg` to decode. If `ffmpeg` is missing AND the user is on openai-whisper, offer to install it:

```bash
brew install ffmpeg
```

Cheap, universally useful, safe to install without heavy deliberation.

## Step 1 — transcribe

After Step 0, the basic recipe:

```bash
AUDIO_PATH="$1"
OUT_DIR="$(dirname "$AUDIO_PATH")"

whisper "$AUDIO_PATH" \
    --model tiny \
    --language en \
    --output_format txt \
    --output_dir "$OUT_DIR" \
    --fp16 False
```

`tiny` model trades a bit of accuracy for 10× speed; fine for voice memos. Upgrade to `base` or `small` only if the user complains about quality.

`--language en` skips whisper's language-detection pass (cuts a few seconds). Drop the flag only when the user explicitly sends non-English audio.

The CLI writes `<basename>.txt` into `$OUT_DIR`. Read that, strip whitespace, surface to the user (or feed into whatever skill called this).

### Output handling

- Always show the user the transcript verbatim on the first pass — they should see what the model heard before any downstream action fires.
- For very short (< 10 word) transcripts: just fold into the next message without a heading.
- For long (> 60 sec audio) transcripts: render as a short block quote so it's visually distinct from agent text.

## Common patterns

### "Transcribe this voice memo"

Step 0 + Step 1 + show transcript. Done.

### "Remind me to call Jamie tomorrow at 2pm" (voice memo)

Step 0 + Step 1 → transcript is "remind me to call Jamie tomorrow at 2pm" → forward to the `reminders` skill.

### "Take these notes" (voice memo of meeting notes)

Step 0 + Step 1 → then ask the user: "Save as a memory page, a plain note, or send back as-is?" Don't auto-ingest — voice memos often contain half-thoughts the user doesn't want filed forever.

## Guardrails

- **Local only.** Never upload audio to a remote service (OpenAI Whisper API, Deepgram, etc.). Herald's voice promise is "stays on your Mac"; this skill upholds that.
- **File-size sanity.** Reject audio longer than 10 minutes unless the user explicitly confirms — model-tiny transcription scales linearly with duration and a 30-min podcast will tie up the model for minutes.
- **Don't re-transcribe.** If a `.txt` already exists alongside the audio file, check its mtime — if newer than the audio, read it directly instead of re-running whisper. Saves time + tokens.
- **Cleanup.** Leave the output `.txt` in place — the user might want to refer back. Deletion is their choice.

## Not in scope (yet)

- **Real-time / streaming transcription.** That's the #308 voice-mode territory.
- **Speaker diarization** ("who said what"). Whisper alone doesn't do this; needs pyannote or similar. File a follow-up issue if the user asks.
- **Non-English transcription quality tuning.** Tiny model handles English cleanly; for other languages, upgrade to `base` or `small` and mention the quality trade-off.

## Related

- `optional-deps` — bulk inventory if the user wants to check several CLIs at once.
- `pdf-extract` — same self-install pattern for a different tool (`opendataloader-pdf`).
- `reminders` — natural downstream when the voice memo is a task.
- `wiki-ingest` — natural downstream when the voice memo is a durable note.
