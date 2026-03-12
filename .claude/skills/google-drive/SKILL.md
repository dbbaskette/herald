---
name: google-drive
description: >
  Manages Google Drive via gws CLI — list, search, upload, download, and share files.
  Use when asked about files in Drive, sharing documents, or finding stored files.
---

# Google Drive Skill

Manage Google Drive using the `gws` CLI tool (Google Workspace CLI). All commands use `--format json` for parseable output.

## Prerequisites

The `gws` CLI must be installed and authenticated with Drive scopes. Test with:

```bash
gws drive files list --params '{"pageSize": 5}' --format json
```

If this fails with an auth error, tell the user: "Google Workspace CLI (`gws`) is not authenticated for Drive. Run `source .env && gws auth login -s drive` — see docs/gws-setup.md."

## Commands

### List Files

```bash
gws drive files list --params '{"pageSize": 10, "orderBy": "modifiedTime desc", "fields": "files(id,name,mimeType,modifiedTime,size,webViewLink)"}' --format json
```

- Default to 10 results unless the user asks for more.
- `orderBy` options: `modifiedTime desc`, `name`, `createdTime desc`, `folder,name`.
- Always include `fields` to limit response size.

**Use for:** "What files are in my Drive?", "Show my recent documents", "List my Drive files"

### Search Files

```bash
gws drive files list --params '{"q": "<QUERY>", "pageSize": 10, "fields": "files(id,name,mimeType,modifiedTime,size,webViewLink)"}' --format json
```

#### Query Syntax (`q` parameter)

| Search | Query |
|--------|-------|
| By name | `name contains 'budget'` |
| By exact name | `name = 'Q1 Report.docx'` |
| By type (Google Docs) | `mimeType = 'application/vnd.google-apps.document'` |
| By type (Spreadsheets) | `mimeType = 'application/vnd.google-apps.spreadsheet'` |
| By type (Presentations) | `mimeType = 'application/vnd.google-apps.presentation'` |
| By type (Folders) | `mimeType = 'application/vnd.google-apps.folder'` |
| By type (PDFs) | `mimeType = 'application/pdf'` |
| In a folder | `'<FOLDER_ID>' in parents` |
| Starred | `starred = true` |
| Shared with me | `sharedWithMe = true` |
| Recent (last 7 days) | `modifiedTime > '2026-03-04T00:00:00'` |
| Trashed | `trashed = true` |
| Combine with AND | `name contains 'report' and mimeType = 'application/pdf'` |
| Full-text search | `fullText contains 'quarterly review'` |

**Use for:** "Find my budget spreadsheet", "Search Drive for project proposal", "Show me shared files"

### Get File Metadata

```bash
gws drive files get --params '{"fileId": "<FILE_ID>", "fields": "id,name,mimeType,modifiedTime,size,webViewLink,owners,sharingUser,permissions"}' --format json
```

**Use for:** "Who owns this file?", "Get details about <file>"

### List Files in a Folder

```bash
gws drive files list --params '{"q": "'\"'\"'<FOLDER_ID>'\"'\"' in parents", "pageSize": 20, "fields": "files(id,name,mimeType,modifiedTime,size)"}' --format json
```

To navigate into a folder, first search for the folder by name, get its ID, then list contents.

**Use for:** "What's in my Project folder?", "Show contents of the Reports folder"

### Create a Folder

```bash
gws drive files create --json '{"name": "<FOLDER_NAME>", "mimeType": "application/vnd.google-apps.folder"}' --format json
```

- Confirm with the user before creating.
- To create inside another folder, add `"parents": ["<PARENT_FOLDER_ID>"]`.

**Use for:** "Create a new folder called Projects", "Make a Drive folder for Q2"

### Share a File

```bash
gws drive permissions create --params '{"fileId": "<FILE_ID>"}' --json '{"role": "<ROLE>", "type": "user", "emailAddress": "<EMAIL>"}' --format json
```

- Roles: `reader`, `writer`, `commenter`, `owner`
- Types: `user`, `group`, `domain`, `anyone`
- Always confirm before sharing: "Share **<filename>** with <email> as <role>?"
- For link sharing: `{"role": "reader", "type": "anyone"}`

**Use for:** "Share this file with alice@example.com", "Make this file public", "Give Bob edit access"

### Delete a File

```bash
gws drive files delete --params '{"fileId": "<FILE_ID>"}'
```

- Always list/search first to confirm the correct file.
- Confirm with the user before deleting: "Delete **<filename>**? This moves it to trash."

**Use for:** "Delete that old report", "Remove the draft document"

### Export Google Doc as PDF

```bash
gws drive files export --params '{"fileId": "<FILE_ID>", "mimeType": "application/pdf"}' > output.pdf
```

- Google Docs → `application/pdf`, `text/plain`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`
- Google Sheets → `application/pdf`, `text/csv`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- Google Slides → `application/pdf`, `application/vnd.openxmlformats-officedocument.presentationml.presentation`

**Use for:** "Export my doc as PDF", "Download the spreadsheet as CSV"

## Parsing JSON Output

The `files.list` response contains a `files` array. Each file has:
- `id` — file ID (needed for get/delete/share/export)
- `name` — file name
- `mimeType` — MIME type (determines file type)
- `modifiedTime` — last modified timestamp (RFC 3339)
- `size` — file size in bytes (not set for Google Docs/Sheets/Slides)
- `webViewLink` — URL to open the file in browser

## Common MIME Types

| Type | MIME |
|------|------|
| Google Doc | `application/vnd.google-apps.document` |
| Google Sheet | `application/vnd.google-apps.spreadsheet` |
| Google Slides | `application/vnd.google-apps.presentation` |
| Folder | `application/vnd.google-apps.folder` |
| PDF | `application/pdf` |
| Word | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| Excel | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |

## Response Formatting

Format responses as clean Telegram-friendly messages (Markdown):

- **File list:** `• **File Name** — Type · Modified <date>` (one per line). Include link if helpful.
- **Empty results:** "No files found matching '<query>'."
- **Shared:** "Shared **<filename>** with <email> as <role>."
- **Created folder:** "Created folder: **<name>**."
- **Deleted:** "Deleted: **<filename>**."

## Error Handling

| Error | Response |
|-------|----------|
| `gws: command not found` | "The `gws` CLI is not installed. Run `brew install googleworkspace-cli` — see docs/gws-setup.md." |
| `403 insufficient scopes` | "Drive access not authorized. Run `source .env && gws auth login -s drive`." |
| `401 authentication` | "Google auth has expired. Run `gws auth login -s drive` to re-authenticate." |
| `404 file not found` | "That file wasn't found. It may have been deleted or you may not have access." |
| No results | "No files found matching your search." |
