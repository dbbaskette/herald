# Google Workspace CLI (`gws`) Setup Guide

Herald uses the [Google Workspace CLI](https://github.com/nicholasgasior/gws) (`gws`) to interact with Gmail, Google Calendar, and Google Drive via skills. This is **optional** — Herald runs without it, but Google-related skills will be unavailable.

---

## 1. Install gws

```bash
brew install googleworkspace-cli
```

Verify:

```bash
gws --version
```

---

## 2. Create OAuth 2.0 Credentials

You need a Google Cloud project with OAuth credentials. Herald's project is called **Herald**.

1. Go to [Google Cloud Console → APIs & Services → Credentials](https://console.cloud.google.com/apis/credentials)
2. Select the **Herald** project (or create one)
3. Ensure these APIs are enabled under **APIs & Services → Library**:
   - Gmail API
   - Google Calendar API
   - Google Drive API
4. Click **Create Credentials → OAuth client ID**
5. Application type: **Desktop app**
6. Name it (e.g., "Herald CLI")
7. Copy the **Client ID** and **Client Secret**

---

## 3. Configure Credentials

Add the OAuth client credentials to your `.env` file:

```bash
GOOGLE_WORKSPACE_CLI_CLIENT_ID=your-client-id.apps.googleusercontent.com
GOOGLE_WORKSPACE_CLI_CLIENT_SECRET=your-client-secret
```

The `gws` CLI reads these environment variables automatically — no need to download a `client_secret.json` file.

Alternatively, you can place a `client_secret.json` at `~/.config/gws/client_secret.json`:

```bash
# Download from Google Cloud Console → Credentials → your OAuth client → Download JSON
cp ~/Downloads/client_secret_*.json ~/.config/gws/client_secret.json
```

---

## 4. Authenticate (One-Time)

Source your `.env` first, then run the OAuth login flow:

```bash
source .env
gws auth login -s gmail,calendar,drive
```

This opens a browser for Google account authorization. Grant access to Gmail, Calendar, and Drive. The refresh token is stored locally at `~/.config/gws/credentials.enc` and persists across sessions.

Check status:

```bash
gws auth status
```

You should see `"auth_method": "oauth2"` and `"token_cache_exists": true`.

---

## 5. Verify

```bash
# Gmail — list recent threads
gws gmail users threads list --params '{"userId": "me", "maxResults": 3}' --format json

# Calendar — list today's events
gws calendar events list --params '{"calendarId": "primary", "timeMin": "'$(date -u +%Y-%m-%dT00:00:00Z)'", "maxResults": 5}' --format json

# Drive — list recent files
gws drive files list --params '{"pageSize": 3}' --format json
```

All should return JSON without auth errors.

---

## 6. Herald Integration

Once `gws` is authenticated, restart Herald (`./run.sh restart bot`). On startup:

- **Configured:** Logs `Google Workspace CLI (gws) detected: <version>` and enables Gmail/Calendar/Drive skills
- **Not configured:** Logs a warning; Google skills return a setup error message

Herald checks `gws auth status` on startup and warns in the logs if auth is missing.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `gws: command not found` | `brew install googleworkspace-cli` |
| `No OAuth client configured` | Add `GOOGLE_WORKSPACE_CLI_CLIENT_ID` and `GOOGLE_WORKSPACE_CLI_CLIENT_SECRET` to `.env`, then `source .env` |
| `Token expired / 401` | Re-run `gws auth login -s gmail,calendar,drive` |
| `restricted_client` error | Your OAuth consent screen is in "Testing" mode — add your Google account as a test user in Cloud Console → OAuth consent screen |
| Wrong account | `gws auth logout` then `gws auth login` again |

---

## Scopes

The `-s gmail,calendar,drive` flag requests scopes for all three services. If you only want a subset:

```bash
gws auth login -s gmail,calendar   # Gmail + Calendar only
gws auth login -s gmail            # Gmail only
```

Herald's skills will gracefully error if the required scope isn't authorized.
