# Obsidian CLI Setup Guide

Herald uses the Obsidian CLI to interact with your Obsidian vault — searching notes, managing tasks, reading/writing content, and more.
This is an **optional** dependency — Herald runs without it, but Obsidian-related skills will be unavailable.

> **Requires Obsidian 1.12+** with the latest installer. See [Obsidian CLI docs](https://help.obsidian.md/cli) for full reference.

---

## 1. Update Obsidian

Make sure you're running **Obsidian 1.12 or later** with the latest installer version (1.12.4+).

- Check your version: **Settings → About**
- If needed, download the latest installer from [obsidian.md/download](https://obsidian.md/download)

---

## 2. Enable the CLI

1. Open Obsidian
2. Go to **Settings → General**
3. Toggle **Command line interface** ON
4. Follow the prompt to register the CLI on your system PATH

---

## 3. Verify (restart your terminal first)

Close and reopen your terminal, then run:

```bash
obsidian version
```

You should see the Obsidian version number. If `obsidian: command not found`:

### macOS

The CLI lives at `/Applications/Obsidian.app/Contents/MacOS/obsidian`. The installer adds this to `~/.zprofile`. If it's missing, add it manually:

```bash
echo 'export PATH="$PATH:/Applications/Obsidian.app/Contents/MacOS"' >> ~/.zprofile
source ~/.zprofile
```

If you use **bash** instead of zsh, add to `~/.bash_profile` instead.

### Linux

The installer creates a symlink at `/usr/local/bin/obsidian`. If missing:

```bash
sudo ln -s /path/to/obsidian /usr/local/bin/obsidian
```

For AppImage installs, the symlink points to the `.AppImage` file directly.

---

## 4. Create the Herald Vault

Herald expects a vault named **Herald-Memory** for storing notes, daily summaries, and context:

1. Open Obsidian
2. Click **Create new vault**
3. Name it `Herald-Memory`
4. Choose a location (default is fine)

Or via CLI:

```bash
obsidian vault:create name="Herald-Memory"
```

Verify it appears:

```bash
obsidian vault
```

Herald will automatically create the following folder structure inside the vault on first use:

```
Herald-Memory/
├── Chat-Sessions/    # Archived conversations
├── Daily/            # Daily journals and briefings
├── Research/         # Web research and deep dives
├── Projects/         # Project-specific notes
├── People/           # Contact and people notes
├── Reference/        # How-tos and config snippets
└── Templates/        # Note templates (auto-created)
```

---

## 5. Test Basic Commands

With Obsidian running, try:

```bash
# Show vault info
obsidian vault

# Search your vault
obsidian search query="test"

# Read today's daily note
obsidian daily:read

# List incomplete tasks
obsidian tasks todo
```

---

## 6. Herald Integration

Once the `obsidian` CLI is on your PATH, Obsidian is running, and the **Herald-Memory** vault exists, Herald's Obsidian skill activates automatically. No additional Herald configuration is needed.

Herald uses the CLI for:

- **Searching notes** — `obsidian search query="..."`
- **Reading/writing notes** — `obsidian read`, `obsidian create`, `obsidian append`
- **Daily notes** — `obsidian daily:read`, `obsidian daily:append`
- **Task management** — `obsidian tasks todo`, `obsidian task ... toggle`
- **Tags and properties** — `obsidian tags`, `obsidian property:set`
- **Morning briefings** — pulls daily note content and open tasks

> **Note:** Obsidian must be running for the CLI to work. If Herald tries to use the Obsidian skill while Obsidian is closed, the first command will launch Obsidian automatically.

---

## Reference

Full CLI documentation: [https://help.obsidian.md/cli](https://help.obsidian.md/cli)
