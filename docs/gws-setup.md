# Google Workspace CLI (`gws`) Setup Guide

Herald uses the Google Workspace CLI (`gws`) to interact with Gmail and Google Calendar.
This is an **optional** dependency — Herald runs without it, but Google-related skills will be unavailable.

> **Note:** `gws` is pre-v1.0. Pin the exact version to avoid breaking changes.

---

## 1. Install gws

```bash
npm install -g @googleworkspace/cli@0.5.0
```

Verify the installation:

```bash
gws --version
```

---

## 2. Create OAuth 2.0 Credentials

1. Go to the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a new project (or select an existing one).
3. Navigate to **APIs & Services → Library** and enable:
   - **Gmail API**
   - **Google Calendar API**
   - **Google Drive API**
4. Navigate to **APIs & Services → Credentials**.
5. Click **Create Credentials → OAuth client ID**.
6. Select **Desktop app** as the application type.
7. Download the credentials JSON file.
8. Place the file where `gws` expects it (typically `~/.gws/credentials.json`), or pass it during login.

---

## 3. Authenticate

Run the OAuth flow:

```bash
gws auth login
```

Follow the browser prompts to grant access. Once complete, `gws` stores tokens locally for future use.

---

## 4. Verify

Confirm everything works by running these commands:

```bash
# List Gmail threads
gws gmail threads list --format json

# List Calendar events
gws calendar events list --format json
```

Both should return JSON output without errors.

---

## 5. Herald Integration

Once `gws` is installed and authenticated, restart Herald. On startup, Herald checks for `gws` in your PATH:

- **Found:** Logs `Google Workspace CLI (gws) detected: <version>` and enables Google skills.
- **Not found:** Logs a warning with a pointer to this setup guide. Google skills return an error message until `gws` is configured.

No additional Herald configuration is needed — the `GwsTools` component discovers `gws` automatically.

---

## Version Pinning

Because `gws` is pre-v1.0, always pin the version you install:

```bash
npm install -g @googleworkspace/cli@0.5.0
```

If you upgrade, test the verification commands above before restarting Herald.
