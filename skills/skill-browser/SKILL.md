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

## Step 3 — install

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
- **Only install from trusted catalogs.** For v1, that's `anthropics/skills` only. Third-party catalogs (ClawHub, random GitHub repos) are scoped out and tracked in a separate issue.
- **Don't overwrite existing skills silently.** If `~/.herald/skills/<name>/` already exists, confirm with the user before replacing. Offer `install-as <newname>` for side-by-side comparisons.
- **Check file sizes.** Skills should be < 500 KB each in total. If a skill's files cross that cap (large assets, datasets), surface the size and ask before fetching.
- **Respect Herald's bundled skills.** The repo's own `skills/` directory is classpath-bundled at build time. Don't try to write there at runtime — the user should edit the source instead. This skill only writes to `~/.herald/skills/` (the runtime dir).
- **Audit after install.** If the new skill references other CLIs (e.g. `pdf` needs `pdftotext`, `xlsx` needs `python` + `openpyxl`), mention the `optional-deps` skill as the next step — don't auto-install transitive deps.

## Uninstall

```bash
rm -rf ~/.herald/skills/<name>/
```

Confirm the user wants it gone first. The `SkillsWatcher` notices the removal and drops the skill from the next turn.

## Not in scope (tracked elsewhere)

- **ClawHub integration.** [clawhub.ai/skills](https://clawhub.ai/skills?sort=downloads) has an install-link pattern on each skill; first-class integration is a separate issue. For now, if the user wants a ClawHub skill, they can download the files manually and drop them in `~/.herald/skills/`.
- **Skill updates.** This skill doesn't diff against upstream or auto-update. Use it as a one-time install; re-run to refresh.
- **Auto-discovery.** This isn't a "when the user asks for something Herald can't do, automatically browse and install" — too magical, too easy to silently pull in unwanted tooling. The user asks explicitly.

## Related

- `optional-deps` — for when a newly-installed skill needs a CLI tool.
- `skills/skill-creator/` — if the catalog doesn't have what you need, create your own.
