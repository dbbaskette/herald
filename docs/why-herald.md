# Why Run Herald

You already have Claude in a browser tab. Maybe ChatGPT too. Why run anything?

Because a browser tab forgets who you are every time you close it, can't wake you up at 7am with a briefing, can't read your calendar, can't tell you which PRs went stale overnight, and can't be scripted against with a tiny file. Herald can do all of those — on hardware you already own, with API keys you already have, and with memory that *actually compounds* instead of resetting on refresh.

## The premise

Herald is an open-source AI agent that you run on your Mac. It has two personalities, from the same JAR:

1. **An always-on personal assistant** — talks to you on Telegram, remembers everything that matters, manages your Gmail and Calendar, delegates deep research to specialist subagents, and runs on a cron.
2. **A single-shot task runner** — point it at an `agents.md` file, hand it a prompt, get a result, done. No daemon, no database.

The split between these is literally one CLI flag. The same agent loop, the same tool system, the same multi-provider model routing powers both.

## What it feels like to run it

You're at coffee. Phone buzzes. It's Herald:

> Morning Dan. You have three meetings today — the 10am is the one you wanted
> prep for. Two new PRs need review (#293, #297). The auth-rewrite branch has
> been idle 6 days — want me to draft a status update?

You reply: "yes, and add Jamie."

Later, you paste a Karpathy gist URL and say "ingest this." Herald fetches it, extracts the key concepts, creates a `sources/karpathy-llm-wiki.md` page, creates or updates the related `concepts/` and `entities/` pages, and logs the whole thing.

A week later you ask: "What did that Karpathy thing say about file-based memory again?" Herald greps its memory directory, loads the relevant pages, and answers you — **with citations to specific files it wrote down last week**. Not a hallucination. Not "I recall from training data." The actual sentences it saved, with the URL it came from.

That's the core bet: **memory that compounds, in plain Markdown, on your disk.**

## Why file-based memory matters

Most AI tools treat memory as an afterthought — a "remember this" button that drops strings into a vector store you can't inspect. Herald treats memory as a first-class artifact:

- Every memory is a Markdown file with YAML frontmatter (`type: concept`, `type: entity`, `type: source`, etc.) in a directory you own.
- `MEMORY.md` is the catalog — grouped by type, updated in place.
- `log.md` is an append-only audit trail of every memory mutation.
- `hot.md` is a refreshable session summary that survives context compaction.
- Three skills (`wiki-ingest` / `wiki-query` / `wiki-lint`) turn the directory into a wiki that grows sharper over time.

Open any file in any editor. Back it up with `git`. Point the directory at an Obsidian vault to get Graph view and backlinks for free. Move everything to a different machine and Herald keeps working. **Your knowledge is not trapped behind someone's API.**

## Why dual-mode earns its complexity

The personal-assistant and task-agent modes aren't two products forced together — they're the same agent with different dials:

- Writing a one-off CSV analysis? `java -jar herald-bot.jar --agents=csv-reporter.md --prompt="..."` — no database touched.
- Want it to watch for new reports and ping you? Add a cron entry. Done.
- Want it to also know about your ongoing projects? Point `HERALD_MEMORIES_DIR` at the same place your assistant uses.

You don't maintain two codebases, two auth stacks, or two mental models. The agents-md task files you write today become reusable components tomorrow.

## What's actually inside

- **All seven Spring AI agentic patterns** (Agent Skills, AskUserQuestion, TodoWrite, Subagent Orchestration, A2A, AutoMemoryTools, Session API) adopted end-to-end.
- **Multi-provider routing** with Anthropic, OpenAI, Gemini, Ollama, LM Studio. Haiku/Sonnet/Opus tiers so subagent work doesn't burn Opus tokens.
- **A Vue 3 management console** for browsing memory, editing skills, and managing cron jobs.
- **Shell & filesystem access** with a regex blocklist, confirmation gating for `sudo`-class operations, and sensitive-value redaction.
- **Google Workspace integration** (Gmail, Calendar) via the `gws` CLI.
- **A2A protocol support** — delegate to remote agents alongside local ones.

## Who this is not for

- If you want a polished consumer app — use ChatGPT or Claude.ai. Herald is a homelab project for people comfortable running JARs and reading config files.
- If you need multi-user hosting or SaaS features — Herald is a single-user agent running on your machine by design.
- If you hate Java — well, it's Java.

## Cost

API costs are whatever your provider charges (Anthropic, OpenAI, Gemini, etc.). The assistant itself costs nothing to run — no subscription, no hosted service, no cloud bill. Free-tier Ollama models work too; mix and match.

## How to start

Ten minutes, no Telegram, no database required:

```bash
git clone https://github.com/dbbaskette/herald.git && cd herald
./mvnw package -DskipTests
export ANTHROPIC_API_KEY=sk-ant-...
java -jar herald-bot/target/herald-bot-*.jar \
    --agents=examples/code-reviewer.md --prompt="review the latest commit"
```

When that feels good, follow [Getting Started](../README.md#getting-started) to add Telegram, memory, and cron.

---

**The pitch in one line:** Herald is a personal AI agent that remembers, runs on schedule, and gets sharper the more you use it — because its memory lives in your filesystem, not someone else's database.
