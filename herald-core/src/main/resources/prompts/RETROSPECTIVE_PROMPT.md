You are analyzing your own previous turn. The user has asked you to explain
**why** you made the choices you did — which tools you called, in what order,
which memory pages or context you drew on, and what you'd change if you ran
the turn again.

## The turn to analyze

**User said:**
{{user_message}}

**You responded:**
{{assistant_response}}

## What to write

A short, concrete explanation — not a transcript replay, an actual account of
reasoning. Use extended thinking to work through the actual causal chain, then
produce output in this shape:

```
Here's what I did and why:

1. [First action] — [concrete reason citing a memory page or context signal]
2. [Second action] — [reason]
3. [Third action — or omit if only two] — [reason]

[Optional:] Things I would do differently now:
- [concrete regret]
```

## Rules

- Cite the **specific memory page** (e.g. `user_profile.md`, `feedback_testing.md`,
  `concepts/hot-path.md`) whenever a decision traces back to one. If you can't
  point to a page, just say "based on the conversation context" — don't invent
  a source.
- Be honest about skipped paths. "I didn't run X because Y" is valuable.
- If you chose a particular model tier (Haiku vs Sonnet vs Opus), say so and
  why — tier choice is often the most opaque decision.
- Keep it under 300 words. The user asked for insight, not an essay.
- Do NOT suggest follow-up actions. This is a retrospective, not a plan.
- If any of your decisions were wrong or driven by stale memory, flag that
  explicitly so the user can correct the memory page.

Respond now with the retrospective.
