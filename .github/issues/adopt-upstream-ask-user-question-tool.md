# Adopt upstream AskUserQuestionTool from spring-ai-agent-utils

## Summary

Herald has a custom `AskUserQuestionTool` that handles simple string-based Q&A via Telegram. The upstream `spring-ai-agent-utils` provides a richer version with structured questions (headers, multiple-choice options, multi-select), a pluggable `QuestionHandler` interface, and answer validation. Adopting it would enable richer Telegram interactions (e.g., inline keyboard buttons) while reducing custom code.

## Current Implementation

Herald's `AskUserQuestionTool` (`herald-bot/src/main/java/com/herald/tools/AskUserQuestionTool.java`):
- Hardwired to `TelegramQuestionHandler` via `ObjectProvider`
- Simple string question in, string answer out
- No structured options or multi-select support

## Upstream API

```java
AskUserQuestionTool askTool = AskUserQuestionTool.builder()
    .questionHandler(questions -> { /* handle questions, return Map<String,String> */ })
    .answersValidation(true)
    .build();
```

Upstream questions include:
- `question`: The question text
- `header`: Short label (max 12 chars)
- `options`: List with `label` and `description` for each choice
- `multiSelect`: Boolean for multiple selections

## Tasks

- [ ] Implement the upstream `QuestionHandler` interface backed by `TelegramQuestionHandler`
- [ ] Map structured options to Telegram inline keyboard buttons where applicable
- [ ] Fall back to free-text input when no options are provided
- [ ] Remove Herald's custom `AskUserQuestionTool` in favor of the upstream version
- [ ] Update `HeraldAgentConfig` wiring
- [ ] Update tests

## References

- [AskUserQuestionTool docs](https://github.com/spring-ai-community/spring-ai-agent-utils/blob/main/spring-ai-agent-utils/docs/AskUserQuestionTool.md)
- [Spring AI blog post](https://spring.io/blog/2026/01/16/spring-ai-ask-user-question-tool/)
- Herald's current impl: `herald-bot/src/main/java/com/herald/tools/AskUserQuestionTool.java`
