# Herald security checklist

Use this with [remote access](remote-access.md) before allowing another device to reach Herald.

- [ ] Bot/internal APIs and management endpoints remain on loopback or a strictly private container network; no public 8081 mapping. The UI token does not protect direct bot access.
- [ ] Prefer Tailscale Serve or a loopback SSH tunnel. Tailnet access is restricted to intended users/devices; no Funnel or router port forwarding.
- [ ] Set a randomly generated `HERALD_UI_AUTH_BEARER_TOKEN`, protect `.env`, restart the console and verify unauthenticated API calls return 401.
- [ ] Use HTTPS and Secure cookies for remote access. An insecure-cookie exception is limited to local HTTP/SSH loopback use.
- [ ] Run `./run.sh config validate` with the effective startup overrides and review `./run.sh doctor` warnings. Verify actual listeners after startup.
- [ ] For public access, require identity/MFA at an authenticated reverse proxy, HTTPS only, rate limits and access restrictions where feasible. Proxy only the console; support SSE without buffering.
- [ ] Configure `HERALD_TELEGRAM_ALLOWED_CHAT_ID` for the intended chat. Do not assume proposed DM pairing is implemented.
- [ ] Keep the current MeetingNotes webhook local. If using an external authenticated gateway, rotate its secret on gateway and exporter together, then revoke the old one. Generic webhook authentication is separate future work.
- [ ] Review generic console auth/CSRF failure logs without logging Authorization headers or cookies. Apply rate limits at the remote ingress.
- [ ] Rotate compromised/shared credentials and restart to invalidate sessions and open streams. Session logout alone does not abort an already-open SSE connection.
- [ ] Keep shell confirmation enabled, restrict integration credentials, back up private data and review installed skills/tools. Console access grants the assistant's local powers.

The default remains single-user local operation with no console token required. This checklist is operational guidance, not an assertion that built-in OAuth, WAF, bot authentication or exactly-once external delivery exists.
