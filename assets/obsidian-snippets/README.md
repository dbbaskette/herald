# Obsidian Snippets for Herald

**Optional.** These files only apply when your `HERALD_MEMORIES_DIR` lives inside an Obsidian vault (Phase E `obsidian-vault` mode). Herald does **not** install or require any of this — the wiki is plain Markdown and works in every editor.

## What's here

| File | Purpose |
|---|---|
| `type-folder-colors.css` | CSS snippet that tints the Herald memory subfolders (`concepts/`, `entities/`, `sources/`, etc.) so each type is visually distinct in the file tree. |
| `memory-dashboard.base` | Obsidian Bases definition — one dashboard view per memory type. Drop into your vault and open as a Base. |

## Installing the CSS snippet

1. Copy `type-folder-colors.css` into `<your-vault>/.obsidian/snippets/`.
2. In Obsidian: Settings → Appearance → CSS snippets → toggle `type-folder-colors` on.

## Installing the Bases dashboard

1. Copy `memory-dashboard.base` into any folder inside your vault (recommended: `Dashboards/`).
2. In Obsidian 1.12+: open the file. The Bases core plugin renders it as a multi-view dashboard.

## Why opt-in

- CSS snippets apply only to your Obsidian install — no effect on other viewers.
- Bases requires Obsidian 1.12+ and the Bases core plugin. Not everyone runs Obsidian.
- Keeping these out of the repo's `.obsidian/` directory means Herald never writes to your vault config on startup.

If you don't run Obsidian, ignore this folder entirely.
