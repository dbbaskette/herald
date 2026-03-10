---
name: gmail
description: >
  Manages Gmail via gws CLI — list unread emails, search messages, read threads,
  create drafts, and manage labels. Use when asked about email, inbox, messages,
  or drafting replies.
---

# Gmail Skill

Manage Gmail using the `gws` CLI tool. All commands use `--format json` for parseable output.

**SAFETY: The agent NEVER sends emails directly — only creates drafts. Always confirm draft content with the user before creating.**

## Prerequisites

The `gws` CLI must be installed and authenticated. Test with:

```bash
gws gmail threads list --query "is:unread" --max-results 1 --format json
```

If this fails with an auth error, tell the user: "Google Workspace CLI (`gws`) is not configured. Run `gws auth login` to authenticate."

## Commands

### List Unread Emails

```bash
gws gmail threads list --query "is:unread" --format json
```

- Returns unread threads from the inbox.
- To limit results: add `--max-results <N>` (default: 10 most recent).
- For unread from a specific sender: `--query "is:unread from:<email>"`.

**Use for:** "Any important unread emails?", "Check my inbox", "Do I have new messages?"

### Search Messages

```bash
gws gmail threads list --query "<SEARCH_QUERY>" --format json
```

- Uses Gmail search syntax for the `--query` parameter.
- Common query operators:
  - `from:<sender>` — messages from a specific person
  - `to:<recipient>` — messages sent to someone
  - `subject:<text>` — subject line contains text
  - `after:YYYY/MM/DD` / `before:YYYY/MM/DD` — date range
  - `has:attachment` — messages with attachments
  - `label:<name>` — messages with a specific label
  - `is:starred` / `is:important` — flagged messages
- Combine operators: `from:alice subject:project after:2026/01/01`

**Use for:** "Find emails from Alice about the project", "Show me messages from last week with attachments", "Search for emails about the budget"

### Read Thread

Retrieve the full conversation thread:

```bash
gws gmail threads get --thread-id <THREAD_ID> --format json
```

- Use a thread ID obtained from a list or search command.
- Returns all messages in the conversation thread.
- Summarize the thread: participants, topic, key points, and any action items.

**Use for:** "Summarize the thread about the Q2 planning", "What was discussed in that email chain?", "Read the conversation with Bob"

### Read Message

Retrieve a single message:

```bash
gws gmail messages get --message-id <MESSAGE_ID> --format json
```

- Use a message ID obtained from a list or search command.
- Returns the full message with headers, body, and attachments list.

**Use for:** "Read the latest email from Alice", "Show me that message about the invoice"

### Create Draft

```bash
gws gmail drafts create --to "<RECIPIENT_EMAIL>" --subject "<SUBJECT>" --body "<BODY>" --format json
```

- **NEVER send emails directly — always create drafts.**
- Before creating the draft, use AskUserQuestion to confirm the content:
  - Show the recipient, subject, and body to the user.
  - Only proceed after explicit confirmation.
- For replies, include the original subject (prepend "Re: " if not already present).
- For CC recipients, add `--cc "<EMAIL>"` if supported.
- Compose the body in a professional tone unless the user specifies otherwise.

**Use for:** "Draft a reply to the last email from Alice", "Write an email to Bob about the meeting", "Compose a follow-up to that thread"

### List Labels

```bash
gws gmail labels list --format json
```

- Returns all Gmail labels (both system and user-created).
- Useful for filtering and organizing searches.

**Use for:** "What labels do I have?", "Show my Gmail categories"

## Parsing JSON Output

### Thread List Response

The JSON output from thread list contains a `threads` array. Each thread has:
- `id` — thread ID (needed for reading the full thread)
- `snippet` — preview text of the latest message
- `historyId` — for change tracking

### Thread Detail Response

A thread detail contains a `messages` array. Each message has:
- `id` — message ID
- `threadId` — parent thread ID
- `labelIds` — applied labels (INBOX, UNREAD, SENT, etc.)
- `snippet` — preview text
- `payload.headers` — array of header objects with `name` and `value`:
  - `From`, `To`, `Cc`, `Subject`, `Date`
- `payload.body` or `payload.parts` — message body content
- `internalDate` — timestamp in milliseconds

### Draft Response

A created draft returns:
- `id` — draft ID
- `message.id` — message ID of the draft
- `message.threadId` — thread ID if it's a reply

## Response Formatting

Format responses as clean Telegram-friendly messages (Markdown):

- **Unread list:** `• **From:** <sender> — <subject>` followed by a one-line snippet. One per thread, sorted by most recent first.
- **Thread summary:** Start with participants and date range, then bullet-point the key messages and any action items. Highlight action items with bold.
- **Search results:** `• **<subject>** from <sender> (<date>)` — one per thread with snippet.
- **Draft created:** "Draft created: **<subject>** to <recipient>. Open Gmail to review and send."
- **Empty results:** "No emails found matching that search." or "Your inbox is all caught up — no unread messages."
- **Labels list:** Bullet list of label names, grouped by system vs. user labels.

## Error Handling

| Error | Response |
|-------|----------|
| `gws: command not found` | "The `gws` CLI is not installed. Install it with `brew install gws` or see the gws documentation." |
| Authentication failure / token expired | "Google auth has expired. Run `gws auth login` to re-authenticate." |
| No results found | "No emails found matching that search." |
| Rate limit exceeded | "Gmail rate limit reached. Wait a moment and try again." |
| Invalid thread/message ID | "That thread or message could not be found. It may have been deleted." |
| Network error | "Couldn't reach Gmail. Check your internet connection." |

## Example Interactions

**"Any important unread emails?"**
→ List unread threads. Summarize each with sender, subject, and snippet. Highlight any that appear urgent or action-required.

**"Find emails from Alice about the project"**
→ Search with `--query "from:alice subject:project"`. Show matching threads with dates and snippets.

**"Draft a reply to the last email from Alice"**
→ Search for recent emails from Alice. Read the most recent thread. Compose a contextually appropriate reply. Show the draft to the user for confirmation via AskUserQuestion. Only create the draft after approval.

**"Summarize the thread about the Q2 planning"**
→ Search for threads matching "Q2 planning". Read the full thread. Provide a summary with participants, key decisions, and action items.

**"What labels do I have?"**
→ List all labels. Group into system labels (Inbox, Sent, Drafts, etc.) and user-created labels.
