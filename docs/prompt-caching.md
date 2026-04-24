# Prompt Caching in Herald

Herald uses Anthropic's [prompt caching](https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching) to cut input-token cost by 75–90% on steady-state load. **Cache reads are 10% of base input price. Cache writes are 125%.** The math only works if the cache is actually hit — which means every byte of the cacheable prefix must be identical from turn to turn.

This doc is for contributors. If you touch the system prompt, tool list, memory injection, or any other per-turn payload, read this first.

## The rule

> **Anything that contributes to the prompt prefix must be deterministically ordered.**
>
> Maps, sets, file-system walks, classpath scans, network results — every unordered source of elements that ends up in the model's input must be sorted (or otherwise canonicalized) before the prompt is built.

Non-determinism anywhere in the prefix invalidates the cache entry for every request on the same conversation.

## Where Herald caches today

Strategy: `AnthropicCacheStrategy.SYSTEM_AND_TOOLS` (the default since [#313](https://github.com/dbbaskette/herald/pull/314)).

That means Anthropic places cache breakpoints on:

1. **The system prompt** — Herald's built system message, including:
   - `DateTimePromptAdvisor` injection
   - `CONTEXT.md` via `ContextMdAdvisor`
   - `hot.md` via `HotMdAdvisor`
   - `MEMORY.md` + memory-tool prompt via `HeraldAutoMemoryAdvisor`
   - `AUTO_MEMORY_SYSTEM_PROMPT.md` body
   - Any `agents.md` persona in task-mode
2. **The tool definitions** — the list of `ToolCallback` objects in `AnthropicChatOptions.toolCallbacks`, plus Herald's loaded skills (via `ReloadableSkillsTool`), plus remote A2A agents.

Conversation history is NOT cached in Herald's default config. (Flip to `CONVERSATION_HISTORY` only if you're running a workload with very long, slowly-growing chats.)

## Hot spots — places that MUST stay deterministic

| Producer | Current state | How |
|---|---|---|
| `ReloadableSkillsTool` | ✅ stable | Sorted by skill name (case-insensitive) after directory + classpath scan. |
| `HeraldConfig.a2aAgents()` | ✅ stable | Sorted by agent name (case-insensitive) on access. |
| `HeraldAgentConfig.activeToolNames` | ✅ stable | Hard-coded literal list plus conditional additions in a fixed order. |
| `HeraldAgentConfig.buildToolList` | ✅ stable | Same — always adds in the same sequence. |
| `MEMORY.md` sections + entries | ⚠️ agent-maintained | See "Agent-maintained artifacts" below. |
| MCP-discovered tools (future) | ❓ not yet wired | When MCP client support lands ([#245](https://github.com/dbbaskette/herald/issues/245)), sort discovered tools by name. |

Anything you add to this pipeline needs an entry in this table and, ideally, a test in [`PromptStabilityTest`](../herald-core/src/test/java/com/herald/agent/PromptStabilityTest.java).

## Agent-maintained artifacts

`MEMORY.md` is the interesting case — the agent writes it via `MemoryCreate` / `MemoryStrReplace` / `MemoryInsert`, so we can't sort it mechanically from Herald's side. We compensate in two places:

- **Prompt-level guidance.** `AUTO_MEMORY_SYSTEM_PROMPT.md` instructs the model to keep type sections in a fixed order and entries within each section alphabetical.
- **Lint-level enforcement.** `skills/wiki-lint/SKILL.md` flags ordering drift under "Cache-stability warnings" when the user runs an audit.

If the agent is sloppy about this, cache hit rate tanks. Watch for it in `/model status` — sudden drops in cache-read tokens often point back to a chaotic `MEMORY.md` edit.

## Observability

Two surfaces:

**Per-turn:** `AgentMetrics` records `cache_read_tokens` and `cache_write_tokens` alongside input/output tokens. Structured JSON log at INFO level on every turn.

**Daily:** `/model status` in Telegram shows:

```
Tokens today: 45.2K in / 18.1K out
Prompt cache today: 680K read / 12K write (93.8% hit rate)
```

A hit rate above 80% is healthy. Below 40% is a sign something's churning the prefix — start looking.

## Debugging a cache regression

When hit rate drops:

1. **Check `log.md`.** A burst of `CREATE` / `STRREPLACE` events on `MEMORY.md` or skill files right before the drop is the usual culprit.
2. **Diff two turns' prompts.** Enable `/trace on` (#307), run two identical-looking turns, and diff the dump files in `~/.herald/prompt-dump/`. Any delta in the cached region is your problem.
3. **Run `wiki-lint`.** If a new section or out-of-order entry slipped into `MEMORY.md`, the lint report's "Cache-stability warnings" section will flag it.
4. **Audit ordering.** If you recently added code that contributes to the system prompt or tool list, walk through that code path and confirm the ordering is fixed.

## For reviewers

When reviewing a PR that touches any of the hot spots in the table above, demand an answer to: **"Is the output byte-identical across JVMs and restarts?"** If the answer is "probably" or "usually," that's a cache-regression hazard and needs a test in `PromptStabilityTest` before merge.

## Not in scope (yet)

- **Cross-provider caching.** OpenAI and Gemini have different cache semantics. Herald's OpenAI/Gemini turns currently pay full price. Tracked in the issue body of #313.
- **Explicit cache-control breakpoints.** Spring AI's `SYSTEM_AND_TOOLS` strategy places breakpoints automatically — we haven't needed to override. If we ever do, note it here.
- **Fine-grained per-skill cache markers.** Possible future optimization — currently every skill is part of one large cached block.
