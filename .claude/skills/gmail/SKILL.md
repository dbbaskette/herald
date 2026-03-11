---
name: gmail
description: >
  Manages Gmail via gws CLI — list unread emails, search messages, read threads,
  create drafts, and manage labels. Use when asked about email, inbox, messages,
  or drafting replies.
---

# Gmail Skill

Manage Gmail using the `gws` CLI tool (Google Workspace CLI). All commands use `--format json` for parseable output.

**SAFETY: The agent NEVER sends emails directly — only creates drafts. Always confirm draft content with the user before creating.**

## Prerequisites

The `gws` CLI must be installed and authenticated with Gmail scopes. Test with:

```bash
gws gmail users threads list --params '{"userId": "me", "maxResults": 1}' --format json
```

If this fails with an auth error, tell the user: "Google Workspace CLI (`gws`) is not authenticated for Gmail. Run `source .env && gws auth login -s gmail` — see docs/gws-setup.md."

## Commands

### List Unread Emails

```bash
gws gmail users threads list --params '{"userId": "me", "q": "is:unread", "maxResults": 10}' --format json
```

- Returns unread threads from the inbox.
- For unread from a specific sender: `"q": "is:unread from:alice@example.com"`

**Use for:** "Any important unread emails?", "Check my inbox", "Do I have new messages?"

### Search Messages

```bash
gws gmail users threads list --params '{"userId": "me", "q": "<SEARCH_QUERY>", "maxResults": 10}' --format json
```

- Uses Gmail search syntax in the `q` parameter.
- Common query operators:
  - `from:<sender>` — messages from a specific person
  - `to:<recipient>` — messages sent to someone
  - `subject:<text>` — subject line contains text
  - `after:YYYY/MM/DD` / `before:YYYY/MM/DD` — date range
  - `has:attachment` — messages with attachments
  - `label:<name>` — messages with a specific label
  - `is:starred` / `is:important` — flagged messages
- Combine operators: `from:alice subject:project after:2026/01/01`

**Use for:** "Find emails from Alice about the project", "Show me messages from last week with attachments"

### Read Thread

```bash
gws gmail users threads get --params '{"userId": "me", "id": "<THREAD_ID>"}' --format json
```

- Use a thread ID obtained from a list or search command.
- Returns all messages in the conversation thread.
- Summarize the thread: participants, topic, key points, and any action items.

**Use for:** "Summarize the thread about the Q2 planning", "Read the conversation with Bob"

### Read Message

```bash
gws gmail users messages get --params '{"userId": "me", "id": "<MESSAGE_ID>"}' --format json
```

- Use a message ID obtained from a list or search command.
- Returns the full message with headers, body, and attachments list.

**Use for:** "Read the latest email from Alice", "Show me that message about the invoice"

### Create Draft

```bash
gws gmail users drafts create --params '{"userId": "me"}' --json '{"message": {"raw": "<BASE64_RFC2822>"}}' --format json
```

To construct the `raw` field, build an RFC 2822 message and base64url-encode it:

```bash
echo -e "To: recipient@example.com\nSubject: Re: Topic\nContent-Type: text/plain; charset=utf-8\n\nBody text here" | base64 | tr '+/' '-_' | tr -d '='
```

- **NEVER send emails directly — always create drafts.**
- Before creating the draft, use AskUserQuestion to confirm the content:
  - Show the recipient, subject, and body to the user.
  - Only proceed after explicit confirmation.

**Use for:** "Draft a reply to the last email from Alice", "Write an email to Bob about the meeting"

### List Labels

```bash
gws gmail users labels list --params '{"userId": "me"}' --format json
```

**Use for:** "What labels do I have?", "Show my Gmail categories"

## Parsing JSON Output

### Thread List Response

The JSON output contains a `threads` array. Each thread has:
- `id` — thread ID (needed for reading the full thread)
- `snippet` — preview text of the latest message
- `historyId` — for change tracking

### Thread Detail Response

A thread detail contains a `messages` array. Each message has:
- `id` — message ID
- `threadId` — parent thread ID
- `labelIds` — applied labels (INBOX, UNREAD, SENT, etc.)
- `snippet` — preview text
- `payload.headers` — array of header objects with `name` and `value`: `From`, `To`, `Cc`, `Subject`, `Date`
- `payload.body` or `payload.parts` — message body content (may be base64-encoded)
- `internalDate` — timestamp in milliseconds

### Decoding Message Body

Message bodies in `payload.body.data` or `payload.parts[].body.data` are base64url-encoded. Decode with:

```bash
echo "<base64url_data>" | tr '_-' '/+' | base64 -d
```

## Response Formatting

Format responses as clean Telegram-friendly messages (Markdown):

- **Unread list:** `• **From:** <sender> — <subject>` followed by a one-line snippet.
- **Thread summary:** Participants, date range, key messages, and action items.
- **Search results:** `• **<subject>** from <sender> (<date>)` — one per thread with snippet.
- **Draft created:** "Draft created: **<subject>** to <recipient>. Open Gmail to review and send."
- **Empty results:** "No emails found matching that search." or "Inbox is clear — no unread messages."

## Error Handling

| Error | Response |
|-------|----------|
| `gws: command not found` | "The `gws` CLI is not installed. Run `brew install googleworkspace-cli` — see docs/gws-setup.md." |
| `403 insufficient scopes` | "Gmail access not authorized. Run `source .env && gws auth login -s gmail`." |
| `401 authentication` | "Google auth has expired. Run `gws auth login -s gmail` to re-authenticate." |
| No results found | "No emails found matching that search." |
| Rate limit exceeded | "Gmail rate limit reached. Wait a moment and try again." |
