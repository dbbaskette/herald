---
name: code-writer
description: >
  Delegate real coding tasks (write a script, fix a bug, refactor a function,
  add a test, explain a codebase) to Claude Code running as a subprocess via
  `claude -p`. Use whenever the user asks Herald to write, modify, or review
  code — especially anything multi-file, anything that should actually run to
  verify, or anything longer than ~50 lines. Also use when the user says
  "write a script for me", "hack together a tool that does X", "refactor this
  function", "add tests for this", or "explain what this code does." Keeps
  Herald's main agent context clean while the coding specialist does its work.
---

# Code Writer — delegation to Claude Code

Herald's main agent is good at lots of things; reading a whole repo and writing careful multi-file patches isn't one of them. Claude Code is purpose-built for that and has its own agentic loop (Read / Edit / Glob / Grep / Bash / etc.). This skill lets Herald invoke Claude Code as a short-lived subprocess for a single coding task and then fold the result back into the conversation.

**This is NOT a tool you call directly.** Use `shell` to run `claude -p`, then surface the produced code or a summary back to the user.

## When to use

| Task | Use this skill? |
|---|---|
| "Write a python script that parses this CSV and dumps JSON" | ✅ Yes |
| "Refactor `apply_config` to use the new builder" | ✅ Yes |
| "Add tests for `user_repository.py`" | ✅ Yes |
| "Explain what this class does" | ⚠️ Maybe — for small files the main agent can just read + explain inline. Delegate only when it's a real codebase-wide investigation. |
| "Fix this SQL" (one line) | ❌ No — answer inline. |
| "Show me a snippet for X" | ❌ No — answer inline. The main agent writes snippets fine; don't pay the subprocess startup cost. |

Rule of thumb: if the output would span **more than ~50 lines** or **more than one file**, delegate. Otherwise answer inline.

## Step 0 — ensure Claude Code is installed

**Run this before invoking.** Idempotent.

```bash
command -v claude
```

### If `claude` is missing

Confirm via `askUserQuestion`:

> To delegate real coding tasks I use Claude Code (the `claude` CLI from
> Anthropic, ~30 MB). Install via `npm install -g @anthropic-ai/claude-code`?
> (Requires Node 18+.)

Then:

```bash
npm install -g @anthropic-ai/claude-code
claude --version
```

If `npm` isn't available → tell the user to install Node 18+ first (`brew install node` or their preferred version manager). Don't guess-install Node yourself.

### Auth

Claude Code uses the user's Anthropic credentials (via their API key or their Claude.ai account login, depending on setup). On first invocation it may prompt for login — that's fine, the user handles it. If `claude -p "hi"` returns an auth error, tell the user:

> Claude Code isn't authenticated. Run `claude login` in your terminal
> (or set `ANTHROPIC_API_KEY` in your shell env). Then try again.

Don't attempt to log in programmatically — the flow needs browser interaction.

## Step 1 — invoke claude -p

The core invocation:

```bash
claude -p "<task prompt>"
```

Non-interactive. Claude Code runs its agent loop, uses its tools (Read/Edit/Bash/etc.) to accomplish the task, prints the final assistant message to stdout. Exits when done.

### Picking the working directory

Claude Code runs **in the current shell's working directory** and operates on files relative to it. Two patterns:

**Pattern A — scratch directory** (default for new scripts):

```bash
SCRATCH=$(mktemp -d -t herald-code-XXXXXX)
cd "$SCRATCH"
claude -p "Write a python script parse_logs.py that reads stdin, extracts timestamps matching HTTP 5xx, and emits a summary of counts-per-minute. Add a --help flag."
```

Scratch dir is wiped manually; safe to iterate in. Good for "write me a script" where the file doesn't need to live anywhere specific.

**Pattern B — existing repo** (bug fixes, refactors):

```bash
cd "$USER_PROJECT_PATH"
claude -p "In src/agent/AgentService.java, the streamChat method has a race between cacheReadRef and cacheWriteRef updates. Fix the race, run the test suite, and summarize what you changed."
```

Claude Code will Read, Edit, run tests, and fold the results into its final message. Confirm the working directory with the user before invoking on an existing repo — edits are real.

### Composing a good task prompt

Claude Code is an agent, not a text generator. A good prompt for it looks like an engineering ticket, not a prose request. Include:

1. **What to produce** — file paths, language, interface shape.
2. **Where to put it** — relative paths only.
3. **What "done" means** — "runs without errors on this sample input" / "all tests pass" / "lints clean."
4. **Context it needs** — mention specific files/functions/conventions that matter. Don't dump entire files; claude will Read what it needs.
5. **Constraints** — "no new dependencies," "Python 3.11+," "follow the style in X."

Example (good):

> Write `tools/dedup.py` in Python 3.11 that reads CSV rows from stdin, deduplicates by the `email` column (case-insensitive), and writes deduped rows to stdout in the original order. Preserve the header. Handle gzip-compressed input when stdin is gzip. Add a `--verify` flag that asserts counts reduce monotonically. Run it against `samples/contacts.csv` to confirm it works; report the before/after row counts.

Example (bad):

> Write something to dedupe a CSV

## Step 2 — surface the result

Claude Code's stdout is the assistant's final message, typically a summary of what it did plus any notable gotchas. Capture and present it:

```bash
claude -p "$PROMPT" 2>&1 | tee "$OUT_LOG"
echo "---"
echo "Files touched:"
cd "$CWD" && git status --short 2>/dev/null || ls -lat | head
```

Show the user:
1. The summary (Claude Code's final output).
2. What files were created or modified.
3. Where it all lives.

If the user wants to run the produced code, invoke it in a separate shell turn — don't chain into execution inside the same delegation.

## Recipes

### "Write me a script that does X"

```bash
SCRATCH=$(mktemp -d -t herald-code-XXXXXX)
cd "$SCRATCH"
claude -p "Write <name>.<ext> that does X. When done, run it with a representative input and confirm the output looks right."
ls -la "$SCRATCH"
```

Report the path + summary to the user; offer to move the file somewhere permanent if useful.

### "Fix this bug" (existing repo)

```bash
cd "$REPO_PATH"
claude -p "<description of bug, pointing at specific file/test>. Fix it, run the test suite, summarize the root cause + fix."
git -C "$REPO_PATH" diff --stat | cat
```

Show the diff summary to the user. Encourage them to review before committing — never auto-commit from this skill.

### "Add tests for this function"

```bash
cd "$REPO_PATH"
claude -p "Add unit tests for <function>. Follow the existing test-file layout for this project. Run the new tests to confirm they pass."
```

### "Explain what this codebase does"

For anything beyond a single small file:

```bash
cd "$REPO_PATH"
claude -p "Read the repo structure and give me a 10-bullet summary of the architecture, key modules, and entry points. No code changes."
```

Fast, doesn't mutate the tree, gives the main agent an index it can hand back to the user.

## Guardrails

- **Working directory is real.** Claude Code edits files directly. Confirm the working dir with the user before any "repo-modifying" invocation. Scratch dirs are fine to use silently.
- **Don't chain destructive invocations.** One task per `claude -p` call. If the user wants "fix the bug AND deploy," do them as separate, confirmed steps.
- **Surface Claude Code's own confirmations.** If it reports unsafe operations or prompts for approval, bubble that up — don't paper over it.
- **Timeouts.** Complex refactors can take 2–5 minutes. Set a generous timeout (10 minutes default); if the call exceeds it, kill it and tell the user rather than blocking the main agent forever.
- **Never embed user secrets in the task prompt.** Claude Code logs prompts locally; don't paste API keys or passwords into `-p "..."`. If the task needs a secret, tell the user to set it in the shell env first.
- **Respect Herald's budget rails.** Claude Code counts against the user's Anthropic usage. When `/budget` has a daily cap near the ceiling (#319), warn before burning significant tokens on a delegation.

## Not in scope

- Running Claude Code **interactively**. That's a separate use case — the user should just open a terminal and run `claude` directly.
- **Multi-turn conversations** with Claude Code from Herald. `claude -p` is one-shot; for iterative coding sessions, hand the user to the CLI directly.
- **Replacing Herald's main model** with Claude Code. That's a provider-level change (issue [#257](https://github.com/dbbaskette/herald/issues/257)); this skill is the simpler "delegate specific tasks" path.

## Related

- `optional-deps` — bulk inventory (will now list `claude` alongside whisper, pdftotext, etc.).
- `pdf-extract`, `voice-handling` — same self-install pattern.
- Herald's task/subagent dispatch — different mechanism (in-process ChatClient); use this skill instead when the task is clearly "write code to disk," not "research a topic and return text."
