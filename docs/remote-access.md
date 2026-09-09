# Remote access

Herald's console (8080) and bot (8081) now bind to `127.0.0.1` by default. The console can read private memory, edit files and run agent tools. Enable console authentication before sharing access. The bot's internal API has no general authentication: keep it on loopback and proxy only the console.

## Console authentication

Generate a random token with `openssl rand -hex 32`, then place the result in your private `.env` as `HERALD_UI_AUTH_BEARER_TOKEN`. Restrict that file with `chmod 600 .env`. Restart the console. Do not commit or put this token in URLs, screenshots or logs. Tokens must contain 32–4096 non-whitespace characters. Unset means local access without authentication.

The browser asks for the token and exchanges it for an opaque, HttpOnly, SameSite=Strict session cookie. The token is not stored in browser storage. Sessions expire after eight hours; **Lock console** revokes that session. Unsaved editors stay mounted behind the lock so signing back in restores the draft. A server restart invalidates every session. Rotate the token by replacing the environment value and restarting the console. Existing SSE connections are authenticated when opened and can continue until they disconnect or time out; restart the console when immediate connection revocation is required.

Cookies are Secure by default (`HERALD_UI_AUTH_SECURE_COOKIE=true`). Use the HTTPS address below. For a strictly loopback HTTP browser or SSH tunnel only, explicitly set `HERALD_UI_AUTH_SECURE_COOKIE=false` and restart. A login that cannot round-trip its cookie reports an error. Keep Secure enabled for remote HTTPS.

API clients send `Authorization: Bearer <token>` on every `/api/**` request, including streams, uploads and proxies. Browser sessions use a CSRF header for mutations, with same-origin protection for chat SSE actions. Cookie-based chat streaming needs a modern browser with Fetch Metadata on HTTPS or localhost; clients without it must use bearer authentication or a CSRF-header request. Static application assets and `/auth/session` bootstrap are public; they contain no personal application data. This is one shared credential, not multi-user authorization. Do not host untrusted applications on the same console origin.

## 1. Tailscale (recommended)

1. Install Tailscale on the Mac hosting Herald and on your phone using the [official downloads](https://tailscale.com/download). Sign both into your tailnet.
2. Enable [MagicDNS](https://tailscale.com/docs/features/magicdns) and [HTTPS certificates](https://tailscale.com/docs/how-to/set-up-https-certificates). Certificate hostnames appear in public Certificate Transparency logs; choose a non-sensitive machine name.
3. Keep Herald listening on loopback, enable the console token above, and run:

   ```sh
   tailscale serve --bg http://127.0.0.1:8080
   tailscale serve status
   ```

4. Open the HTTPS URL printed by Serve from your phone while Tailscale is connected, for example `https://my-mac.example-tailnet.ts.net`. Enter your console token. The public-facing HTTPS port is normally 443, not Herald's internal 8080.
5. Restrict the tailnet's [access policy](https://tailscale.com/docs/features/access-control) to your intended users/devices. Do not enable Funnel or router port forwarding for Herald.

[Serve](https://tailscale.com/docs/features/tailscale-serve) provides tailnet-only HTTPS proxying to the loopback service; merely installing Tailscale does not turn port 8080 into HTTPS. It does not require opening Herald's ports to the internet. See the [Serve CLI reference](https://tailscale.com/docs/reference/tailscale-cli/serve) for platform setup and disabling a configured service.

## 2. SSH tunnel (occasional access)

With SSH access to your Mac already configured, run from the other computer:

```sh
ssh -N -L 127.0.0.1:8080:127.0.0.1:8080 user@mac.local
```

Visit `http://127.0.0.1:8080` locally; use another local port if occupied. For cookie login through this HTTP loopback endpoint, use the explicit Secure-cookie exception described above. The tunnel carries traffic encrypted between the computers. Console proxy endpoints reach the bot on the Mac, so forwarding bot port 8081 is unnecessary. Keep the forwarding listener bound to loopback, use SSH keys, and close SSH when finished.

## 3. Public internet (discouraged)

Prefer either private option. If public access is necessary, place an identity-aware HTTPS reverse proxy/tunnel (for example Cloudflare Access) in front of **only the console**, require identity/MFA at that boundary, enable Herald's token, retain Secure cookies, and rate-limit authentication and API requests. Preserve same-origin browser behavior and streaming; disable proxy buffering for SSE. Keep all bot/internal/management ports private. Follow the [security checklist](security-checklist.md).

Herald does not implement bot-wide OAuth/basic authentication, Telegram DM pairing (#303), or the generic authenticated-webhook proposal (#304). Its existing MeetingNotes webhook is a local unauthenticated bot endpoint. Do not publish it; remote exporters need a separately authenticated gateway. Rotate any gateway webhook secret in both gateway and exporter, test the new value, then revoke the previous value. Console token rotation does not secure a directly exposed bot endpoint.

## Validate configuration and migrate existing installs

After building (`mvn -pl herald-ui -am -DskipTests package`), run `./run.sh config validate`. It resolves the console's Spring configuration without starting a server or database. Pass the same Spring command-line overrides as startup, for example `./run.sh config validate --server.address=0.0.0.0`. Exit codes: 0 acceptable exposure settings, 1 warning, 2 invalid configuration. `./run.sh doctor` also checks environment bind/auth settings; for custom Spring configuration files use the validator. Neither check audits reverse proxy or tailnet access policy.

Existing LAN/container users must opt in to a broad listener with `HERALD_UI_BIND_ADDRESS=0.0.0.0`; enable authentication first. `HERALD_BOT_BIND_ADDRESS` independently controls the bot. Container loopback is inside the container: use a private container network for bot/UI communication, and publish only the UI to host loopback (for example `127.0.0.1:8080:8080`) for a local TLS proxy. Never publish the unauthenticated bot to the internet. Spring's `SERVER_ADDRESS` or `--server.address` overrides these defaults, so audit existing launch arguments too. The example macOS UI launch agent now binds loopback.
