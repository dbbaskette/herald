# TelegramSender MarkdownV2 fallback cascade is fragile and hard to debug

## Summary

`TelegramSender` attempts to send messages in three formats with automatic fallback: MarkdownV2 → escaped MarkdownV2 → plain text. Each attempt catches exceptions and retries with a simpler format. This three-level cascade is hard to debug (users can't tell which format was used), produces inconsistent formatting depending on message content, and the escaping logic in `MessageFormatter.escapeMarkdownV2()` may itself introduce errors.

## Current Behavior

1. Try sending with MarkdownV2 parse mode
2. If that fails (Telegram API rejects malformed markdown), escape the content and retry as MarkdownV2
3. If that also fails, send as plain text (no parse mode)

Plus: 3 retries with exponential backoff for each attempt, and 429 rate-limit handling with `retryAfter`.

## Issues

1. **Silent format degradation**: Users see plain text instead of formatted text with no indication. Agent's carefully crafted markdown (code blocks, bold, links) disappears silently.
2. **Double-escaping risk**: If the first attempt fails on partially-valid markdown, the escape pass may double-escape already-valid sequences
3. **Retry multiplication**: 3 formats × 3 retries = up to 9 API calls for a single message that has bad markdown
4. **No logging of which format succeeded**: Debugging formatting issues requires adding temporary logging
5. **Split messages lose context**: Messages split at 4096 chars may split in the middle of a markdown construct (e.g., a code block), causing the first part to fail and the second to succeed with different formats

## Proposed Fix

### Short-term
- Add DEBUG logging indicating which format attempt succeeded
- Add a counter metric for format fallbacks (`herald.telegram.format.fallback`)
- Reduce retry count for fallback attempts (don't retry 3 times per format)

### Medium-term
- Parse the markdown before sending and pre-validate it against Telegram's MarkdownV2 rules
- Fix the markdown at the parsing stage rather than falling back
- Consider always using HTML parse mode (Telegram also supports it), which has simpler escaping rules

### Long-term
- Use a proper Markdown → TelegramMarkdownV2 converter library

## Tasks

- [ ] Add logging for format fallback events
- [ ] Add Micrometer counter for format fallbacks
- [ ] Consider switching to HTML parse mode (simpler escaping)
- [ ] Fix message splitting to respect markdown block boundaries
- [ ] Evaluate Telegram markdown converter libraries

## References

- `herald-bot/src/main/java/com/herald/telegram/TelegramSender.java`
- `herald-bot/src/main/java/com/herald/telegram/MessageFormatter.java`
