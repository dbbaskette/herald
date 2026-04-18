# Getting Started with Herald — 101

New to Herald? Start here. This guide walks you from zero to your first working agent in about ten minutes, with no Telegram, no database, and no prior Spring/Java experience required. Once it's working, you'll know what Herald is, how to talk to it, and where to go next.

## What is Herald?

Herald is a program that runs an AI assistant powered by Claude (and optionally other models). You can run it two ways:

- **Task agent** — give it a prompt, it does the job, it exits. Good for one-off automation. No setup beyond a file and an API key.
- **Personal assistant** — an always-on bot you chat with over Telegram. It remembers things across conversations, checks your calendar/email, and runs on a schedule.

Same program. The mode is just configuration.

## What you'll build here

A tiny task agent named `hello-agent` that you can have a back-and-forth conversation with in your terminal. Nothing is stored, nothing is sent to Telegram. Just you, Claude, and your local machine.

## Prerequisites

For this guide, you need:

| Thing | Why | How to check |
|---|---|---|
| **Java 21+** | Herald is a Java program | `java -version` |
| **Maven wrapper** | To build the JAR (ships with the repo) | `./mvnw -version` |
| **Anthropic API key** | To talk to Claude | Sign up at [console.anthropic.com](https://console.anthropic.com) |

You do **not** need Node.js, a Telegram bot, a database, or a Google account for this guide. Those come later.

## Step 1 — Get the code and build

```bash
git clone https://github.com/dbbaskette/herald.git
cd herald
./mvnw package -DskipTests
```

The build produces `herald-bot/target/herald-bot.jar`. First build takes a few minutes; later builds are fast.

## Step 2 — Write a tiny agent definition

An "agent definition" is a Markdown file with a YAML frontmatter block on top. The frontmatter tells Herald the agent's name, which model to use, and which built-in tools to enable. Everything below the frontmatter is the system prompt — the instructions Claude reads before responding to you.

Create `hello-agent.md` in the repo root:

```markdown
---
name: hello-agent
description: A friendly assistant that can read files
model: sonnet
tools: [filesystem]
---

You are a friendly assistant running locally on the user's machine.
Keep answers short and practical. When the user asks about files or
the project, use the filesystem tool to look things up rather than
guessing.
```

That's it. No code.

## Step 3 — Run it

```bash
export ANTHROPIC_API_KEY=sk-ant-...
java -jar herald-bot/target/herald-bot.jar --agents=hello-agent.md
```

You'll land in an interactive prompt. Try:

```
> hi, who are you?
> what files are in this directory?
> read README.md and tell me in one sentence what Herald is
```

Type `exit` (or Ctrl-C) to quit.

## What just happened?

Five things worth understanding:

1. **You launched `herald-bot.jar` with `--agents=hello-agent.md`.** That flag puts Herald in task-agent mode — no database, no Telegram, just a chat loop in your terminal.
2. **Herald read your agent definition.** The `name`, `model`, and `tools` fields told it how to set up the agent. The prose became the system prompt.
3. **You typed a message.** Herald sent it to Claude along with the system prompt.
4. **Claude might have called a tool.** Because `tools: [filesystem]` was set, Claude could read files off your disk to answer — without you having to paste file contents into chat.
5. **You got a reply.** The loop continues until you exit.

This is the whole model: **definition + prompt + tools → agent**. Everything else Herald does is more of the same with more tools and more persistence.

## A few things to try next

Stay in task-agent mode and experiment:

- **Change the model.** Swap `model: sonnet` for `model: opus` or `model: haiku` and see how the responses differ.
- **Change the tools.** Try `tools: [filesystem, shell]` to let the agent run shell commands. (Careful — the agent can now actually run things on your Mac.)
- **Change the personality.** Rewrite the system prompt so the agent behaves differently. "You are a terse code reviewer" is very different from "You are a patient teacher."
- **Use one of the included examples.** The `examples/` folder has ready-to-run agents: `code-reviewer.md`, `csv-reporter.md`, `report-writer.md`, `cf-analyzer.md`. Try `java -jar herald-bot/target/herald-bot.jar --agents=examples/code-reviewer.md`.
- **One-shot it.** Add `--prompt="..."` to run a single prompt and exit — handy for scripting: `java -jar ... --agents=examples/code-reviewer.md --prompt="review the latest commit"`.

## Vocabulary cheat sheet

You'll see these words a lot in the rest of the docs. Rough definitions:

| Term | What it means, in plain English |
|---|---|
| **Agent** | A configured AI assistant: a model + a system prompt + a set of tools. |
| **Tool** | A thing the agent can *do* (read a file, run a command, send an email). The model decides when to call one. |
| **Skill** | A Markdown file in `skills/` that teaches the agent *how* to do something well (e.g. "how to save a chat session to Obsidian"). Different from a tool — a skill is knowledge, a tool is an action. |
| **Subagent** | An agent the main agent can delegate to (e.g. "go research this topic deeply and report back"). |
| **Advisor** | A Spring AI concept — a piece of code that sits between you and the model and can modify the conversation (add memory, sanitize messages, etc.). You mostly don't need to think about these. |
| **Task agent mode** | `--agents=file.md` — one agent, no database, exits when done. |
| **Personal assistant mode** | Telegram bot + SQLite database + cron jobs. Long-running. What you get if you configure `HERALD_TELEGRAM_BOT_TOKEN` in `.env`. |

## Where to go next

Once the task-agent loop makes sense, here's the ladder:

1. **[Full Getting Started in the README](../README.md#getting-started)** — set up the personal-assistant mode with Telegram, persistent memory, and the web console.
2. **[agents.md spec](agents-md-spec.md)** — every field you can put in an agent definition.
3. **[Obsidian setup](obsidian-setup.md)** — point Herald's memory at an Obsidian vault so your AI's notes are browsable.
4. **[Google Workspace setup](gws-setup.md)** — give Herald access to your Gmail and Calendar.
5. **[Herald patterns comparison](herald-patterns-comparison.md)** — how Herald maps to the Spring AI Agentic Patterns series.
6. **Build your own skill.** Drop a new file into `skills/` — Herald picks it up live, no restart.

## Troubleshooting

- **`Could not find or load main class`** — you didn't build yet, or you're pointing at the wrong JAR path. Re-run `./mvnw package -DskipTests` and use the exact path `herald-bot/target/herald-bot.jar`.
- **`ANTHROPIC_API_KEY is not set`** — `export` it in the same shell before running, or put it in a `.env` file and use `./run.sh`.
- **The agent keeps apologizing instead of reading files** — you probably forgot `tools: [filesystem]` in the frontmatter, or the path you asked about doesn't exist.
- **Compile or Maven errors** — make sure `java -version` says 21 or higher. Older JDKs will not build Herald.

If you get stuck, open an issue at [github.com/dbbaskette/herald/issues](https://github.com/dbbaskette/herald/issues).
