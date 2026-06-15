# ClawHub Protocol Documentation

Research conducted 2026-06-15 by inspecting the ClawHub catalog, individual skill pages,
and API endpoints.

## 1. Install URL Pattern

ClawHub offers three install methods per skill:

| Method | Example |
|--------|---------|
| **OpenClaw CLI** | `openclaw skills install <slug>` |
| **ClawHub CLI** | `clawhub install <slug>` (alias) |
| **Direct download** | `https://wry-manatee-359.convex.site/api/v1/download?slug=<slug>` |
| **Git clone** (if source repo exists) | `git clone https://github.com/<owner>/<slug>.git ~/.openclaw/skills/<slug>` |

The download endpoint returns a ZIP file with `Content-Disposition: attachment; filename="<slug>-<version>.zip"`.
There are no `openclaw://` deep links — install is CLI-based or via direct ZIP download.

## 2. API Endpoints and Response Shapes

ClawHub is backed by Convex (hosted at `wry-manatee-359.convex.site`) with a Vercel frontend.
The public API requires **no authentication**.

### List skills — `GET /api/v1/skills`

**Base URL:** `https://clawhub.ai/api/v1/skills` (proxied to Convex)  
**Also accessible at:** `https://wry-manatee-359.convex.site/api/v1/skills`

**Query parameters:**

| Parameter | Description | Example |
|-----------|-------------|---------|
| `sort` | Sort order | `downloads`, `stars`, `updated`, `newest`, `name` |
| `limit` | Page size (default 30) | `10` |
| `cursor` | Pagination cursor from `nextCursor` | (JSON string) |

**Response shape:**

```json
{
  "items": [
    {
      "slug": "self-improving-agent",
      "displayName": "self-improving agent",
      "summary": "Captures learnings, errors, and corrections...",
      "description": "Captures learnings, errors, and corrections...",
      "tags": {
        "latest": "3.0.23"
      },
      "stats": {
        "comments": 53,
        "downloads": 461444,
        "installsAllTime": 6879,
        "installsCurrent": 6536,
        "stars": 3782,
        "versions": 33
      },
      "createdAt": 1767632598365,
      "updatedAt": 1781293673628,
      "latestVersion": {
        "version": "3.0.23",
        "createdAt": 1781293673628,
        "changelog": "OpenClaw-specific integration...",
        "license": "MIT-0"
      },
      "metadata": {
        "setup": [],
        "os": null,
        "systems": null
      }
    }
  ],
  "nextCursor": "{\"v\":1,\"index\":\"by_active_stats_installs_all_time\",\"key\":[...]}"
}
```

### Download a skill — `GET /api/v1/download`

**URL:** `https://wry-manatee-359.convex.site/api/v1/download?slug=<slug>`

| Parameter | Required | Description |
|-----------|----------|-------------|
| `slug` | yes | Skill slug from the catalog |
| `version` | no | Specific version (defaults to latest) |

**Response:** ZIP file (`application/zip`), filename pattern `<slug>-<version>.zip`.

## 3. Pagination

Cursor-based pagination using the `nextCursor` field:

```
GET /api/v1/skills?sort=downloads&limit=10
→ response includes nextCursor

GET /api/v1/skills?sort=downloads&limit=10&cursor=<nextCursor value>
→ next page
```

The cursor is a JSON-encoded string containing:
```json
{
  "v": 1,
  "index": "by_active_stats_installs_all_time",
  "key": [null, 4635, 1769863429632, "r..."]
}
```

When `nextCursor` is `null` or absent, there are no more pages.
Default page size is 30 items.

## 4. Skill Package Contents

A downloaded skill ZIP contains:

### Minimal skill (e.g., `github`):
```
SKILL.md        — Main skill definition with YAML frontmatter
skill-card.md   — Generated metadata card (publisher, license, risks)
_meta.json      — Package metadata
```

### Full skill with scripts (e.g., `self-improving-agent`):
```
SKILL.md
skill-card.md
_meta.json
README.md
assets/             — Template files (LEARNINGS.md, ERRORS.md, etc.)
hooks/openclaw/     — Hook handlers (handler.js, handler.ts, HOOK.md)
references/         — Documentation (examples.md, hooks-setup.md, etc.)
scripts/            — Shell scripts (activator.sh, error-detector.sh, etc.)
```

### `_meta.json` structure:
```json
{
  "ownerId": "kn70pywhg0fyz996kpa8xj89s57yhv26",
  "slug": "github",
  "version": "1.0.0",
  "publishedAt": 1767545344344
}
```

### SKILL.md frontmatter:
```yaml
---
name: github
description: "Interact with GitHub using the `gh` CLI..."
---
```

This matches the Anthropic/Claude Code skill format — YAML frontmatter with `name` and
`description` fields, followed by markdown content. The `references/` and `scripts/`
directories also align with Herald's existing skill pattern.

## 5. Auth and Rate Limits

### Authentication
**None required.** All endpoints return `access-control-allow-origin: *` (full CORS).
No API keys, tokens, or cookies needed for read/download operations.

### Rate Limits

| Endpoint | Limit | Window | Headers |
|----------|-------|--------|---------|
| `/api/v1/skills` (list) | 3,000 requests | ~37 seconds reset | `ratelimit-limit`, `ratelimit-remaining`, `ratelimit-reset` |
| `/api/v1/download` | 1,200 requests | ~37 seconds reset | Same headers |

Headers follow the standard `ratelimit-*` and `x-ratelimit-*` format:
```
ratelimit-limit: 3000
ratelimit-remaining: 2968
ratelimit-reset: 37
x-ratelimit-limit: 3000
x-ratelimit-remaining: 2968
x-ratelimit-reset: 1781552460
```

These are generous limits — unlikely to be hit in normal usage.

## 6. Skills Inspected

| Skill | Slug | Downloads | Files in ZIP |
|-------|------|-----------|-------------|
| self-improving-agent | `self-improving-agent` | 461,444 | 14 files (SKILL.md, scripts/, hooks/, references/, assets/) |
| Skill Vetter | `skill-vetter` | 258,139 | 3 files (SKILL.md, skill-card.md, _meta.json) |
| Github | `github` | 190,267 | 3 files (SKILL.md, skill-card.md, _meta.json) |

## 7. Key Findings for Herald Integration

1. **Simple REST API** — Two endpoints cover everything: list (`/api/v1/skills`) and download (`/api/v1/download?slug=`).
2. **No auth needed** — Public, CORS-enabled, generous rate limits.
3. **ZIP delivery** — Skills are delivered as ZIP archives, not individual files.
4. **Compatible format** — SKILL.md frontmatter (`name`, `description`) and directory structure (`references/`, `scripts/`) align with Herald's existing skill pattern.
5. **Cursor pagination** — Standard cursor-based pagination for catalog browsing.
6. **Convex backend** — The Convex site URL (`wry-manatee-359.convex.site`) is stable and used directly for downloads; the `/api/v1/skills` endpoint works on both `clawhub.ai` and the Convex domain.
