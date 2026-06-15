---
name: skill-browser
description: >
  Browse, search, and install skills from Anthropic's official skills catalog
  at github.com/anthropics/skills. Use whenever the user asks "do I have a
  skill for X", "is there a skill for Y", "browse available skills", "install
  the <name> skill", or mentions a capability Herald might not support yet
  (Excel / Word / PowerPoint / PDF generation / MCP server building / frontend
  design / Slack GIF creation / ...). Fetches the full skill tree — SKILL.md
  plus any `references/` and `scripts/` — into `~/.herald/skills/` where the
  skills watcher picks it up live (no restart required).
---

# Skill Browser

Turns Herald into an opt-in consumer of Anthropic's growing public skill library. The catalog ships new skills over time; this lets the user pull them in on demand rather than bundling everything up front and bloating the boot-time context.

**This is NOT a tool you call directly.** Use `shell` + `gh api` (or `curl`) to query GitHub, then the filesystem tools to write files under `~/.herald/skills/<name>/`. The `SkillsWatcher` picks up new files with a 250 ms debounce — the skill becomes usable in the next agent turn without a restart.

## Prerequisites

- `gh` CLI (GitHub). Usually already present on dev Macs. If missing:
  ```bash
  command -v gh >/dev/null || brew install gh
  ```
  The unauthenticated API rate limit (60 requests/hour) is plenty for this skill — we only burn a few calls per install. If the user runs into rate limits, `gh auth login` raises it to 5k/hour.
- Herald's skills directory: `~/.herald/skills/` by default (see `HERALD_SKILLS_DIRECTORY`). Confirm it exists and is writable before any install.

## Step 1 — list what's available

```bash
gh api repos/anthropics/skills/contents/skills \
    --jq '.[] | select(.type=="dir") | .name'
```

Current contents (as of April 2026) — fetch fresh via the command, don't trust this list to stay current:

- **Document formats**: `pdf`, `pptx`, `xlsx`, `docx`
- **Web + UI**: `frontend-design`, `web-artifacts-builder`, `webapp-testing`, `canvas-design`, `theme-factory`
- **Content + comms**: `brand-guidelines`, `internal-comms`, `doc-coauthoring`, `slack-gif-creator`
- **Agentic**: `mcp-builder`, `skill-creator`, `claude-api`, `algorithmic-art`

For a one-line description of each, fetch the SKILL.md frontmatter. Keep the description short — only pull full SKILL.md when the user asks about a specific candidate.

### "What's available that I don't already have?"

```bash
# Local installed skills
LOCAL=$(ls -1 ~/.herald/skills/ 2>/dev/null | sort)
# Remote catalog
REMOTE=$(gh api repos/anthropics/skills/contents/skills \
    --jq '.[] | select(.type=="dir") | .name' | sort)
# What's remote but not local
comm -23 <(echo "$REMOTE") <(echo "$LOCAL")
```

## Step 2 — preview a specific skill

Fetch the upstream SKILL.md to show the user **before** installing. They should see what they're pulling in:

```bash
gh api repos/anthropics/skills/contents/skills/<name>/SKILL.md --jq .content | base64 -d
```

Surface:
- The `description` frontmatter field (what triggers it).
- The first 200 words of the body (what it actually does).
- Any unusual dependencies mentioned in the body (e.g. Python packages, CLIs).

Then ask via `askUserQuestion`:

> The `<name>` skill from anthropics/skills does [one-line summary]. Install into `~/.herald/skills/<name>/`?

## Step 3a — install from anthropics/skills

Fetch the full subtree (SKILL.md + any `references/`, `scripts/`, `assets/`, `LICENSE.txt`) and write each into the local skills dir. Use the GitHub contents API recursively — there's no "download zip" via `gh` but the contents API works.

```bash
SKILL_NAME="$1"
DEST="$HOME/.herald/skills/$SKILL_NAME"
mkdir -p "$DEST"

# Recursive fetch helper — GitHub's contents API lists a directory's children
install_tree() {
    local path="$1"  # e.g. skills/pdf or skills/pdf/references
    local local_dir="$2"

    gh api "repos/anthropics/skills/contents/$path" 2>/dev/null \
        | jq -r '.[] | "\(.type) \(.name) \(.download_url // "")"' \
        | while IFS=' ' read -r type name url; do
            if [ "$type" = "file" ]; then
                curl -sL "$url" -o "$local_dir/$name"
            elif [ "$type" = "dir" ]; then
                mkdir -p "$local_dir/$name"
                install_tree "$path/$name" "$local_dir/$name"
            fi
        done
}

install_tree "skills/$SKILL_NAME" "$DEST"

# Inject provenance marker into installed SKILL.md frontmatter
sed -i '' '/^---$/{n; s/^/source: anthropics-skills\n/;}' "$DEST/SKILL.md"

# Verify
if [ -f "$DEST/SKILL.md" ]; then
    echo "Installed $SKILL_NAME → $DEST"
    echo "Files:"
    find "$DEST" -type f | sed "s|$DEST/||" | sort
else
    echo "Install failed — SKILL.md missing at $DEST"
    exit 1
fi
```

If `jq` isn't available, fall back to awk or pipe through a Python one-liner. `jq` is in the `optional-deps` catalog; offer to install it if needed.

## Step 3b — install from ClawHub

ClawHub skills are community-contributed and fetched via the ClawHub API. The install flow is similar to anthropics/skills but includes additional guardrails (blocklist check, size cap, caching — see Guardrails below).

```bash
SKILL_NAME="$1"
AUTHOR="$2"   # ClawHub author/slug, e.g. "jsmith"
DEST="$HOME/.herald/skills/$SKILL_NAME"

# --- Blocklist check ---
BLOCKLIST=~/.herald/clawhub-blocked.txt
if [ -f "$BLOCKLIST" ] && grep -qF "$AUTHOR" "$BLOCKLIST"; then
    echo "Author $AUTHOR is in your ClawHub blocklist ($BLOCKLIST). Aborting."
    echo "Edit $BLOCKLIST to remove the author if this was a mistake."
    exit 1
fi

# --- Fetch skill metadata (uses cache if fresh) ---
CACHE=~/.herald/.clawhub-cache.json
if [ -f "$CACHE" ] && [ $(( $(date +%s) - $(stat -f %m "$CACHE") )) -lt 300 ]; then
    CATALOG=$(cat "$CACHE")
else
    CATALOG=$(curl -sL 'https://clawhub.ai/api/skills' | tee "$CACHE")
fi

# --- Size check (250 KB cap for ClawHub) ---
SKILL_SIZE=$(echo "$CATALOG" | jq -r --arg name "$SKILL_NAME" '.[] | select(.name==$name) | .total_size // 0')
if [ "$SKILL_SIZE" -gt 256000 ]; then
    echo "Skill $SKILL_NAME is ${SKILL_SIZE} bytes — exceeds the 250 KB ClawHub cap. Aborting."
    exit 1
fi

mkdir -p "$DEST"

# Fetch skill files from ClawHub
curl -sL "https://clawhub.ai/api/skills/$SKILL_NAME/files" \
    | jq -r '.[] | "\(.path) \(.download_url)"' \
    | while IFS=' ' read -r path url; do
        mkdir -p "$DEST/$(dirname "$path")"
        curl -sL "$url" -o "$DEST/$path"
    done

# Inject provenance marker into installed SKILL.md frontmatter
sed -i '' '/^---$/{n; s/^/source: clawhub:https:\/\/clawhub.ai\/skills\/'"$SKILL_NAME"'\n/;}' "$DEST/SKILL.md"

# Verify
if [ -f "$DEST/SKILL.md" ]; then
    echo "Installed $SKILL_NAME from ClawHub → $DEST"
    echo "Files:"
    find "$DEST" -type f | sed "s|$DEST/||" | sort
else
    echo "Install failed — SKILL.md missing at $DEST"
    exit 1
fi
```

## Step 4 — trigger reload + report

```bash
# The SkillsWatcher picks up new files within 250 ms, so reload is often
# unnecessary. But issuing it ensures the skills list refreshes before
# the next turn reads it.
```

Herald's `/skills reload` command flushes the cache. You can also reach it via the ReloadableSkillsTool in the next turn — it'll pick up the new skill automatically via the watcher.

Report to the user:
- What was installed + where.
- A one-line summary of what the skill does.
- One example of how to trigger it (pulled from the skill's description field).

## Guardrails

- **Preview before install, always.** SKILL.md can contain arbitrary instructions that run via the agent's tools. The user should see what they're pulling in.
- **Only install from trusted catalogs.** Supported catalogs are `anthropics/skills` and [ClawHub](https://clawhub.ai/skills). Random GitHub repos or unknown URLs are not supported — the user must manually download those.
- **Don't overwrite existing skills silently.** If `~/.herald/skills/<name>/` already exists, confirm with the user before replacing. Offer `install-as <newname>` for side-by-side comparisons.
- **Check file sizes.** anthropics/skills cap: **500 KB** per skill. ClawHub cap: **250 KB** per skill. The lower ClawHub cap reflects the wider risk surface of community-contributed skills — smaller payloads limit the blast radius of any malicious or bloated content. If a skill's files cross the cap, surface the size and ask before fetching.
- **Respect Herald's bundled skills.** The repo's own `skills/` directory is classpath-bundled at build time. Don't try to write there at runtime — the user should edit the source instead. This skill only writes to `~/.herald/skills/` (the runtime dir).
- **Audit after install.** If the new skill references other CLIs (e.g. `pdf` needs `pdftotext`, `xlsx` needs `python` + `openpyxl`), mention the `optional-deps` skill as the next step — don't auto-install transitive deps.

### Provenance markers

Every installed skill's SKILL.md must have a `source:` field in its frontmatter to track where it came from:

- `source: anthropics-skills` — for skills from the official `anthropics/skills` repo
- `source: clawhub:<canonical-url>` — for ClawHub skills (e.g. `source: clawhub:https://clawhub.ai/skills/pdf`)

The `source:` field is injected automatically by the install steps (Step 3a / Step 3b). It is not yet validated by `ValidateSkillTool`, but its presence is a convention that enables future auditing and trust-level decisions.

### Blocklist

Users can maintain a blocklist of ClawHub authors they don't trust at `~/.herald/clawhub-blocked.txt`. One author or slug per line:

```
# ~/.herald/clawhub-blocked.txt
# Lines starting with # are ignored by grep -F but harmless to include.
spammy-author
sketchy-skills-inc
```

Before installing any ClawHub skill, check the author against this file:

```bash
BLOCKLIST=~/.herald/clawhub-blocked.txt
if [ -f "$BLOCKLIST" ] && grep -qF "$AUTHOR" "$BLOCKLIST"; then
    echo "Author $AUTHOR is in your ClawHub blocklist ($BLOCKLIST). Aborting."
    echo "Edit $BLOCKLIST to remove the author if this was a mistake."
    exit 1
fi
```

If the blocklist file doesn't exist, skip the check — no file means no blocks.

### Caching

Cache the ClawHub catalog response locally to avoid hammering the API on repeated browse/search requests. The cache lives at `~/.herald/.clawhub-cache.json` with a **5-minute TTL**.

```bash
CACHE=~/.herald/.clawhub-cache.json
if [ -f "$CACHE" ] && [ $(( $(date +%s) - $(stat -f %m "$CACHE") )) -lt 300 ]; then
    cat "$CACHE"
else
    curl -sL 'https://clawhub.ai/api/skills' | tee "$CACHE"
fi
```

- If the cache file exists and is less than 5 minutes old (300 seconds), read from cache.
- Otherwise, fetch fresh from the network and overwrite the cache file.
- The cache is invalidated automatically on the next request after the TTL expires — no manual purge needed.
- This caching applies only to ClawHub catalog requests. The `anthropics/skills` GitHub API calls are not cached (GitHub has its own rate limits and caching headers).

## Uninstall

```bash
rm -rf ~/.herald/skills/<name>/
```

Confirm the user wants it gone first. The `SkillsWatcher` notices the removal and drops the skill from the next turn.

## Not in scope (tracked elsewhere)

- **Skill updates.** This skill doesn't diff against upstream or auto-update. Use it as a one-time install; re-run to refresh.
- **Auto-discovery.** This isn't a "when the user asks for something Herald can't do, automatically browse and install" — too magical, too easy to silently pull in unwanted tooling. The user asks explicitly.

## Related

- `optional-deps` — for when a newly-installed skill needs a CLI tool.
- `skills/skill-creator/` — if the catalog doesn't have what you need, create your own.
