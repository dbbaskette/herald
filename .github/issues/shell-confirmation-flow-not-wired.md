# Shell confirmation flow is non-functional — confirmCommand() never called

## Summary

`HeraldShellDecorator` implements a confirmation flow for dangerous commands (e.g., `sudo`, redirects to system paths) that sends a Telegram message asking for approval, then blocks on a `CompletableFuture`. However, the `confirmCommand(id, approved)` method that resolves the future is **never called from anywhere** — there is no listener in `TelegramPoller` or `CommandHandler` to handle user YES/NO responses. Every confirmation request will time out after 60 seconds.

## Current Implementation

`HeraldShellDecorator.java`:
- `requiresConfirmation()` detects `sudo`, pipe-to-shell, and system-path redirects
- `handleConfirmation()` sends a Telegram message with a confirmation ID, then blocks the calling thread via `CompletableFuture.get(confirmationTimeoutSeconds)`
- `confirmCommand(String id, boolean approved)` is public but never wired

The TODO at line ~104 says: *"Wire into TelegramPoller to handle user YES/NO responses. AskUserQuestionTool integration is a follow-up."*

## Impact

- Commands requiring confirmation always time out (60s block)
- The agent turn is stalled for the full timeout duration
- Users see a confirmation prompt in Telegram but have no way to respond

## Proposed Fix

### Option A: Wire via AskUserQuestionTool (preferred)
Replace the custom confirmation flow with a call to `AskUserQuestionTool`, which already has the Telegram question/answer plumbing:

```java
// In HeraldShellDecorator
String answer = askUserQuestionTool.call(
    "Allow execution of: " + command + "? (yes/no)");
return "yes".equalsIgnoreCase(answer.trim());
```

### Option B: Wire via CommandHandler
Add a `/confirm <id> yes|no` command handler in `CommandHandler` that calls `shellDecorator.confirmCommand(id, approved)`.

### Option C: Wire via TelegramPoller callback buttons
Use Telegram inline keyboard buttons for yes/no, with a callback that resolves the pending future.

## Tasks

- [ ] Choose approach (A recommended — leverages existing infrastructure)
- [ ] Implement the wiring
- [ ] Add timeout messaging so the user knows when a confirmation expires
- [ ] Add integration test for the confirmation round-trip
- [ ] Remove the TODO comment

## References

- `herald-bot/src/main/java/com/herald/tools/HeraldShellDecorator.java`
- `herald-bot/src/main/java/com/herald/tools/AskUserQuestionTool.java`
- `herald-bot/src/main/java/com/herald/telegram/CommandHandler.java`
