# Herald on Tanzu Platform — Migration Plan

> **Status:** Planning draft. Authored 2026-06-23 from a full feature audit of the
> Herald monorepo (`herald-core`, `herald-persistence`, `herald-telegram`,
> `herald-bot`, `herald-ui`). This document is the working spec for the migration
> we'll develop over the next day or two.

---

## 0. TL;DR — the shape of the work

Herald runs today as a **two-process macOS desktop agent** that assumes:

- a **persistent local filesystem** (`~/.herald/...`) for memory, uploads, skills, prompts, and a **SQLite** database;
- a pile of **local CLIs** (`gws`, `whisper`, `pdftotext`, `markitdown`, `reminders`, `Obsidian`) reached via `sh -c`;
- a **local model** (LM Studio / Ollama on `localhost`);
- a **single instance** (Telegram long-poll, in-memory offset, per-JVM cron scheduling);
- configuration from a **`.env` file** sourced by `run.sh`.

**Tanzu Platform (Cloud Foundry / `cf push`)** breaks every one of those assumptions: the container filesystem is **ephemeral**, there are **no arbitrary CLIs**, the platform may run **multiple instances**, config comes from **bound services / env / CredHub**, and `$PORT` is injected.

The good news: **Herald is already a Spring Boot app that speaks OpenAI-compatible HTTP**, so the *model* story — binding to the Tanzu **GenAI** tile — is the *easy* part. The hard parts are **storage** (SQLite → Postgres + a persistent volume for Markdown) and the **Google Workspace** integration (CLI → API client).

**Recommended posture for v1 on TP:** run **one instance** of a **slimmed "cloud profile" Herald**, bind **GenAI + Postgres + an NFS volume**, keep the **Markdown vault as a directory on the volume** (no Obsidian), keep **Telegram polling** (works fine at 1 instance), and **defer/disable** the desktop-only features (Reminders, Obsidian, MeetingNotes file backstop) and the **shell tool**. Re-platform Google Workspace as a follow-on track.

---

## 1. Target assumptions (confirm these)

| Assumption | Default taken in this plan | Alternative |
|---|---|---|
| "Tanzu Platform" means | **Tanzu Platform for Cloud Foundry** (`cf push`, buildpacks, `manifest.yml`) — consistent with the `cf`/`cfctx` foundation tooling you use | Tanzu Platform for Kubernetes (would change packaging to images/Helm, not config) |
| "Local model" means | **Tanzu GenAI service** (the GenAI tile) bound via `VCAP_SERVICES`, exposing an **OpenAI-compatible** chat + embeddings endpoint | An external Ollama/vLLM endpoint reachable via env var; or keep LM Studio over a tunnel (not recommended) |
| Scale | **1 instance** for v1 (personal agent; no HA requirement) | Multi-instance later (requires the §8 work) |
| Obsidian | **Dropped.** Memory vault stays a **plain Markdown directory** on a persistent volume | — |

> Everything below assumes **TPCF + GenAI tile + 1 instance** unless noted.

---

## 2. Deployment topology

Today: two independently-deployable Spring Boot fat JARs.

- **`herald-bot`** (port 8081) — the agent: Spring AI, tools, Telegram, cron, MCP/A2A, REST API (`/api/chat`, `/api/model`, `/api/approvals`, `/api/meetings`, `/actuator/health`). Depends on `herald-core` + `herald-persistence` + `herald-telegram`.
- **`herald-ui`** (port 8080) — a **thin web frontend + reverse proxy**. **Deliberately does _not_ depend on `herald-core` or Spring AI** (verified in `herald-ui/pom.xml`); it copies prompt resources at build time and talks to the bot over HTTP via `herald.bot.url` (default `http://localhost:8081`). It has its **own** SQLite for UI settings/history.

### Recommended CF topology

Two CF apps + **container-to-container (c2c) internal networking**:

```
                         ┌─────────────────────────────┐
   Telegram  ───────────▶│ herald-bot   (1 instance)   │───▶ GenAI (bound)
   (egress via ASG)      │  route: herald-bot.apps.     │───▶ Postgres (bound)
                         │         internal (c2c only) │───▶ NFS volume (bound)
   Browser ──▶ herald-ui │  + public route (webhooks)  │
   (public route) ──────▶│ herald-ui    (1+ instances) │
                         │  proxies to bot via apps.    │
                         │  internal route + net policy │
                         └─────────────────────────────┘
```

- `herald-bot` gets a **public route** *only if* you switch Telegram to webhooks or expose `/api/meetings/ingest` to the MeetingNotes app. Otherwise it can be **internal-route-only**.
- `herald-ui` gets the **public route** users hit; set `herald.bot.url=https://herald-bot.apps.internal:8081` and add a `cf add-network-policy herald-ui --destination-app herald-bot`.
- **Alternative (simpler, more refactor):** collapse the UI into the bot as one app serving both static UI and API on one `$PORT`. Saves the c2c/network-policy setup but means the UI inherits Spring AI on its classpath (the very thing it was split to avoid). **Keep them separate for v1.**

### `$PORT` binding

The Java buildpack sets `SERVER_PORT=$PORT` automatically, so Spring Boot binds correctly **as long as nothing hard-pins the port at runtime**. Today ports come from `server.port` in `application.yaml` (8081/8080) and `HERALD_SERVER_PORT`. **Action:** ensure no code path overrides `$PORT`; leave `server.port` as a *local* default and let the buildpack win on CF. The UI→bot URL must become the **internal route**, not `localhost:8081`.

---

## 3. Storage — the central problem

Herald writes to the local filesystem and a local SQLite DB in many places. On CF the disk is wiped on every restart/restage/scale. There are **three distinct storage needs**, each with a different solution.

### 3a. Relational data → **bind a Postgres service**

**Today:** SQLite at `~/.herald/herald.db` (WAL mode), via `sqlite-jdbc`, in **two** modules:
- `herald-persistence/.../config/DataSourceConfig.java` (bot) — tables: `messages`, `memory`, `cron_jobs`, `commands`, `model_usage`, `SPRING_AI_CHAT_MEMORY`, `model_overrides`, `settings`, `meetings_ingested`.
- `herald-ui/.../config/DataSourceConfig.java` (UI) — its own SQLite with a similar subset.
- Schema: `herald-persistence/src/main/resources/schema.sql`, idempotent `CREATE TABLE IF NOT EXISTS` (portable).

**Change:**
1. Add `org.postgresql:postgresql` driver; keep `sqlite-jdbc` for local dev (driver chosen by profile).
2. Rework both `DataSourceConfig`s to build the `DataSource` from the **bound service** instead of a hardcoded SQLite path. On TPCF use **`java-cfenv` (Spring Cloud Bindings)** — it auto-reconfigures a `DataSource` from `VCAP_SERVICES` for a bound Postgres instance with **zero code** if we stop hand-constructing the `DataSource`. Simplest path: **delete the custom SQLite `DataSourceConfig` on the `cloud` profile** and let Spring Boot + cfenv autoconfigure from the binding; keep the SQLite config under a `local` profile.
3. Port `schema.sql` to Postgres dialect (SQLite `INSERT OR IGNORE` → `INSERT ... ON CONFLICT DO NOTHING`; check column types — `TEXT`/`INTEGER` map cleanly; any `AUTOINCREMENT` → `BIGSERIAL`/`GENERATED`). Run via Flyway/Liquibase or `spring.sql.init` on first boot.
4. Decide whether bot + UI **share one** Postgres (recommended — single source of truth, lets us eventually drop the UI's separate store) or get **two** databases.

**Effort:** ~½–1 day. **Risk:** low–medium (schema is small and portable).

### 3b. Markdown vault, CONTEXT.md, uploads, writable skills/prompts → **bind an NFS volume service**

These are **file-based** and the user explicitly wants to **keep the Markdown-directory model** (just without Obsidian). The CF-native way to give a container a persistent POSIX filesystem is a **Volume Service (NFS)**: `cf create-service nfs Existing <name> -c '{"share":"…"}'` then `cf bind-service herald-bot <name> -c '{"mount":"/var/data"}'`.

Point the (already env-configurable) paths at the mount:

| Need | Config key | Default today | On CF |
|---|---|---|---|
| Long-term memory vault (`MEMORY.md`, `log.md`, `hot.md`, `*.md`) | `HERALD_MEMORIES_DIR` / `herald.long-term-memory.memories-dir` | `~/.herald/memories` | `/var/data/memories` |
| Standing brief | `HERALD_AGENT_CONTEXT_FILE` | `~/.herald/CONTEXT.md` | `/var/data/CONTEXT.md` |
| Prompt overrides | `HERALD_HOME` | `~/.herald` | `/var/data` |
| Writable user skills | `HERALD_SKILLS_DIRECTORY` | `~/.herald/skills` | `/var/data/skills` |
| **Uploads** | **none — hardcoded** ⚠️ | `~/.herald/uploads` | `/var/data/uploads` |

**⚠️ One code change required:** `herald-core/.../config/HeraldLimits.java:50` hardcodes
`UPLOADS_DIR = Path.of(System.getProperty("user.home"), ".herald", "uploads")`.
Make it env-configurable (`HERALD_UPLOADS_DIR`) like every other path. This is the **only hard blocker** in the storage layer that isn't already a config key.

**Caveat — `SkillsWatcher`:** `herald-core/.../agent/SkillsWatcher.java` uses `WatchService`, which **does not reliably fire on NFS mounts**. It already degrades gracefully (load-at-startup still works); hot-reload of skills just won't trigger automatically. Acceptable for v1; document it.

**Read-only skills/prompts:** prefer **baking the bundled skills + prompts into the classpath/JAR** (`herald.ui.bundled-skills-path`, `PromptLoader` already falls back to classpath). Only *user-authored, writable* skills need the volume. This keeps most of the skill surface immutable and volume-independent.

**Effort:** ~½ day (mostly the one `HeraldLimits` patch + env wiring + provisioning the share). **Risk:** low.

> **Alternative to NFS:** object storage (S3-compatible) with a small storage-abstraction layer over the memory/upload reads & writes. Cleaner cloud-native story, but a real refactor (every `Files.readString`/`Files.write` in the memory + upload paths). **Defer** — NFS volume is the 1-day path that preserves the file model.

### 3c. MeetingNotes read-only backstop → **drop on CF (keep webhook only)**

`herald-persistence/.../meetings/MeetingNotesCatalog.java` opens the **MeetingNotes desktop app's** SQLite (`~/Documents/MeetingNotes/db.sqlite`) read-only and reads per-meeting `summary.md` / `action-items.json` from disk. That app is macOS-only and won't exist on CF. It **already degrades to empty** when the path is missing.

**Change:** keep the **webhook ingest** path (`POST /api/meetings/ingest` → `MeetingIngestService` → Postgres `meetings_ingested`), and **disable the file backstop** on CF by leaving `HERALD_MEETINGNOTES_DB_PATH`/`HERALD_MEETINGNOTES_DIR` empty and the catch-up cron (`HERALD_MEETINGNOTES_CATCHUP_CRON`) set to `-`. The MeetingNotes app (running on your Mac) POSTs to Herald's public route instead. No code change required beyond config.

---

## 4. Model binding — existing OpenAI provider

The startup adapter now maps tagged GenAI/AI Services bindings to the existing
`openai` provider. See [the binding contract and examples](genai-binding.md) for
supported credentials, evidence limits, precedence, selection and sanitized errors.
Discovery uses `genai`/`llm` tags, independent of offering/plan names. A selected
binding supplies endpoint, key, model, catalog and default provider together;
`HERALD_GENAI_BINDING_ENABLED=false` restores local environment/YAML configuration.
The doctor path shares this resolution.

The root manifest demonstrates an existing model service binding, Java 21, one
instance and an internal route only. It is not a complete deployment recipe.
Storage, desktop capability gating, CLI/runtime changes and Google API work remain
separate prerequisites (including #388). No cloud profile is introduced here.
Embedding bindings and remote model discovery are outside this adapter's scope.
Direct providers/failover still require their own configuration and permitted egress.

---

## 5. External CLIs & tools — what breaks and the plan for each

The buildpack container has **none** of these binaries and a locked-down shell. Inventory and disposition:

| CLI / tool | Used for | Where | Today's fallback | TP plan |
|---|---|---|---|---|
| **`gws`** (Google Workspace) | Gmail/Calendar/Drive/Docs/Sheets/Tasks/People + OAuth | `GwsTools` (persistence), `GwsAvailabilityChecker` (core), `GwsAuthController` (ui), `run.sh` | tools return JSON error; degrade | **Biggest refactor — own track (§5a).** v1: **disable** (graceful). v2: reimplement on Google's Java API client + non-interactive refresh token. |
| **`whisper`** | Telegram voice → text | `TelegramPoller.tryTranscribeVoice` | returns null → file saved + path hint | v1: **degrade** (voice saved, not transcribed). v2: hosted STT (GenAI tile transcription or OpenAI Whisper API). |
| **`pdftotext`** | Telegram PDF → text | `TelegramPoller.tryExtractDocumentText` | returns null → file saved + "use markitdown" hint | **Replace with pure-Java PDFBox/Tika** (runs on JVM, no CLI) — easy win, removes a CLI and keeps the feature. |
| **`markitdown`** | rich doc → Markdown | agent-driven via **shell tool** + skill | n/a | Dies with the shell tool. v2: a hosted conversion endpoint or Tika. |
| **`reminders`** | Apple Reminders tools + morning briefing | `RemindersTools` (persistence), `RemindersAvailabilityChecker` (core) | macOS-only; tools return error | **Disable on CF** (already OS-gated; Linux → unavailable). |
| **`Obsidian`** | vault search/read from Settings UI | `herald-ui/.../ObsidianController.java` (hardcoded `/Applications/Obsidian.app/...`) | HTTP 500 | **Remove/disable** — user dropped Obsidian. Vault is a plain dir now. |
| **`gh`** | GitHub PR skill | agent-driven via shell tool | n/a | Dies with shell tool; not core. |
| **`python3`** | `run.sh` gws token sync | `run.sh` | warn + skip | N/A — `run.sh` is replaced by `manifest.yml`. |
| **`lsof`/`pgrep`** | `run.sh` process cleanup | `run.sh` | n/a | N/A on CF. |

### 5a. Google Workspace — the one big functional refactor

**Today** *all* Google functionality shells out to the `gws` CLI, which: (1) won't exist in the container, (2) stores OAuth tokens in the **macOS Keychain**, and (3) authenticates via an **interactive browser flow** (`gws auth login`) — none of which work headless on CF.

**Options (pick on the v2 track):**
- **A. Disable for v1 (recommended start).** Tools already degrade to JSON errors; the agent copes. Ship TP without Google, add it back deliberately.
- **B. Reimplement on Google's Java client libraries** (`google-api-services-gmail`, `-calendar`, etc.) with a **pre-provisioned OAuth refresh token** (obtained once, locally) or a **service account with domain-wide delegation**. Store the refresh token / SA JSON in **CredHub** or a bound credential service, not a file. This is the "optimal" end state but is a multi-file rewrite of `GwsTools`.
- **C. Sidecar.** Keep `gws` running as a companion process **outside** CF (e.g., on your Mac or a small VM) exposing an HTTP/MCP shim; Herald calls it. Preserves the CLI but adds an external moving part. (Note: `application.yaml` already has commented-out **MCP** placeholders for `google-calendar`/`gmail` servers — option C aligns with that.)

**Recommendation:** v1 **disable**; v2 **option B** (Java client + refresh token in CredHub) for the cleanest cloud posture.

### 5b. The `shell` tool — disable on CF

`herald-persistence/.../tools/HeraldShellDecorator.java` exposes a general `sh -c` tool to the model (blocklist + confirmation gate, explicitly **"not a security boundary"**). On a shared platform this is both **useless** (no CLIs to run) and a **security liability**. **Disable it on the `cloud` profile** (don't register the bean), which also cleanly removes the `markitdown`/`gh` agent skills that depend on it. If some controlled execution is wanted later, switch to an explicit **allowlist** mode.

---

## 6. Integrations & runtime topology

### 6a. Telegram — keep polling at 1 instance; webhook is the scale-out path

`herald-telegram/.../TelegramPoller.java` long-polls `getUpdates` every second with an **in-memory offset** and evicts the OkHttp pool after 3 consecutive failures. Telegram allows **only one `getUpdates` consumer per bot token** — so polling is **correct at exactly 1 instance** and **breaks at 2+** (the in-memory offset desyncs, messages drop/double-process).

- **v1 (1 instance):** keep polling. **Must** open **egress to `api.telegram.org:443`** via an **Application Security Group** — the `NoRouteToHostException` we saw locally is the same class of failure you'll hit on CF without an ASG.
- **Scale-out (later):** switch to a **webhook** (`setWebhook` → `POST /api/telegram/webhook` on the bot's public route), make the offset stateless, and add the §8 multi-instance work.

### 6b. Scheduling & cron — fine at 1 instance, needs locking to scale

Every `@Scheduled`/cron fires **per instance**, with **no leader election**:
- `TelegramPoller.poll()` — `fixedDelay=1s` (see above).
- `CronService` (`herald-persistence`) — loads `cron_jobs` from DB at `@PostConstruct` and schedules each on a **per-JVM** `TaskScheduler`. At N instances the **morning briefing / weekly review fire N×** (N× token spend, N× Telegram messages). **This is the highest-cost multi-instance hazard.**
- `MeetingCatchupJob` (6pm daily), `DailyConsolidationTrigger` (first turn/day, DB-flagged), `StatusSseService.pushStatus` (5s), `LmStudioModelDiscovery` (startup).

- **v1 (1 instance):** all correct as-is.
- **Scale-out (later):** add **ShedLock** (DB-backed distributed lock) around `CronService` jobs + `MeetingCatchupJob` + consolidation, or designate a leader. See §8.

### 6c. MeetingNotes

Webhook ingest → Postgres (keep). File/SQLite backstop → disable (see §3c). The MeetingNotes desktop app POSTs to Herald's public route.

### 6d. MCP & A2A

- **MCP client** is **off by default** (`HERALD_MCP_CLIENT_ENABLED=false`); commented-out `google-calendar`/`gmail` SSE server placeholders exist. No change needed unless you adopt **option C** for Google.
- **A2A** (`spring-ai-agent-utils-a2a`): Herald is a **client** of remote agents (registers them as subagents via `herald.a2a.*` URLs); **no server listener is implemented**. Works on CF as long as the remote agent URLs are reachable (egress/ASG). No blocker.

### 6e. Desktop-only tools

`RemindersTools` (macOS `reminders` CLI) and `ObsidianController` (macOS app) — **disable on CF** (Reminders already OS-gates itself; Obsidian is being removed). No `osascript`/AppleScript anywhere else (verified).

---

## 7. Networking, secrets & config

### 7a. Egress (Application Security Groups)

Compile and apply an ASG for the bot. Hosts Herald reaches:

| Host:port | Why | Needed when |
|---|---|---|
| `api.telegram.org:443` | Telegram poll/send/file | always (if Telegram on) |
| **GenAI service** (internal) | model inference + embeddings | always (bound service — usually allowed by platform) |
| **Postgres** (internal) | data | always (bound service) |
| `*.googleapis.com:443`, `accounts.google.com:443` | Google Workspace | only if Google re-enabled |
| `api.anthropic.com:443` | Anthropic (failover/optional) | if configured |
| `api.openai.com:443`, `generativelanguage.googleapis.com:443` | OpenAI/Gemini (optional) | if configured |
| `wttr.in:443` | weather in morning briefing | optional (degrades) |
| `api.search.brave.com:443` | web search | optional (only if key set) |
| `herald-bot.apps.internal:8081` | UI→bot (c2c) | always (network policy) |

`localhost:1234` (LM Studio) / `localhost:11434` (Ollama) are **desktop-only** and disabled on CF.

### 7b. Secrets & config — kill the `.env`, use bindings/env/CredHub

`run.sh` sources a `.env` and syncs `~/.config/gws/client_secret.json`. On CF there is no `.env` and no `run.sh`. Move every secret to **`cf set-env` / bound service credentials / CredHub**:

| Secret | From | To |
|---|---|---|
| `ANTHROPIC_API_KEY` | `.env` | `cf set-env` / CredHub (optional on CF if using GenAI) |
| `HERALD_TELEGRAM_BOT_TOKEN`, `HERALD_TELEGRAM_ALLOWED_CHAT_ID` | `.env` | `cf set-env` / CredHub |
| Model base-url + key | `.env` / localhost | **GenAI binding** (`VCAP_SERVICES`) |
| DB | SQLite file | **Postgres binding** (`VCAP_SERVICES`) |
| Google client id/secret/token | `.env` + Keychain | **CredHub** (only if Google re-enabled) |
| `HERALD_WEB_SEARCH_API_KEY`, weather location | `.env` | `cf set-env` |

Add a Spring **`cloud`/`tanzu` profile** (`application-cloud.yaml`) carrying the CF-specific wiring (disable shell tool, reminders, obsidian, lmstudio discovery; point paths at `/var/data`; select GenAI provider). Activate with `SPRING_PROFILES_ACTIVE=cloud` in the manifest. Keep the current `application.yaml` as the **local/desktop** profile.

### 7c. Logging

Already stdout-only (no `logback.xml`, no file appenders) — **CF-ready**. `run.sh`'s `LOG_DIR` files go away; the platform captures stdout via Loggregator (`cf logs`). No change.

---

## 8. Multi-instance (deferred — only if you need HA/scale)

v1 runs at **1 instance** and needs none of this. To scale `herald-bot` past 1:

1. **Telegram:** switch poll → **webhook**; remove in-memory offset reliance.
2. **Cron:** add **ShedLock** (Postgres lock table) around `CronService`, `MeetingCatchupJob`, and memory consolidation so each job fires once cluster-wide.
3. **SSE/status:** `StatusSseService` emitter lists are per-instance — fine with sticky sessions, otherwise clients may see instance-local status. Consider session affinity on the UI route.
4. **Model discovery / startup tasks:** make idempotent or leader-only.

`herald-ui` (stateless proxy) can scale to N instances freely *today* (modulo its own SQLite — fold it into shared Postgres first).

---

## 9. Build & packaging

- **No `Dockerfile`/`manifest.yml`/`Procfile` exists today** — add them.
- Build the two fat JARs: `./mvnw -pl herald-bot -am package` and `./mvnw -pl herald-ui -am package` (the `spring-boot-maven-plugin` is configured on both).
- Use the **Java buildpack** (`cf push` with `path: target/herald-bot-*.jar`). JDK 21 (set `JBP_CONFIG_OPEN_JDK_JRE` to a Java-21 line).
- Author `manifest.yml` (sketch in §11) with two apps, the `cloud` profile, the volume + service bindings, health-check `http /actuator/health`, and memory (~1.5–2G for the bot; ~768M–1G for the UI).
- Replace `run.sh`'s responsibilities (env load, skills bootstrap, gws sync, recompile, port mgmt) with: classpath-bundled skills, profile config, bound services, and `cf push`.

---

## 10. Code changes checklist (concrete)

**Hard blockers (must do for v1):**
- [ ] `herald-core/.../config/HeraldLimits.java:50` — make `UPLOADS_DIR` read `HERALD_UPLOADS_DIR` (only hardcoded `~/.herald` path left).
- [ ] `herald-persistence/.../config/DataSourceConfig.java` + `herald-ui/.../config/DataSourceConfig.java` — Postgres on the `cloud` profile (prefer cfenv autoconfig; keep SQLite under `local`).
- [ ] `schema.sql` — Postgres dialect (`INSERT OR IGNORE` → `ON CONFLICT DO NOTHING`, types, autoincrement); wire Flyway or `spring.sql.init`.
- [ ] Accept GenAI chat binding mapping to existing `openai` properties. Implementation and synthetic coverage exist; target broker contract verification, the pinned build and the same-JAR smoke result are still required. See the binding guide. A live-foundation check is additional evidence.
- [ ] Configure and verify GenAI embeddings separately.
- [ ] Add `application-cloud.yaml` + a `cloud` profile that: disables `HeraldShellDecorator`, `RemindersTools`/checker, `ObsidianController`, `LmStudioModelDiscovery`; points all paths at `/var/data`; selects GenAI.
- [ ] `manifest.yml` (+ optional `Procfile`), buildpack/JDK config.

**Strongly recommended for v1:**
- [ ] Replace `pdftotext` with **PDFBox/Tika** in `TelegramPoller.tryExtractDocumentText` (removes a CLI, keeps PDF text).
- [ ] Gate Telegram, MCP, A2A, weather, web-search behind config so unconfigured features stay dark.
- [ ] ASG for `api.telegram.org` (+ any optional egress) and a `herald-ui → herald-bot` network policy.

**Follow-on track (v2):**
- [ ] Google Workspace re-platform (`gws` CLI → Google Java client + refresh token in CredHub).
- [ ] Hosted STT for voice (drop `whisper`).
- [ ] Multi-instance: Telegram webhook + ShedLock (§8).
- [ ] Optional: object-store storage abstraction (replace NFS); fold UI SQLite into shared Postgres.

---

## 11. Phased plan (the next day or two)

### Day 1 — "boots on TP, talks to GenAI, remembers"
1. Provision: GenAI service, Postgres service, NFS volume; create a `cloud` Spring profile.
2. Code: `HeraldLimits` uploads env var; Postgres `DataSourceConfig` + `schema.sql`; GenAI chat binding validation; disable shell/reminders/obsidian/lmstudio-discovery on `cloud`.
3. Package both JARs; write `manifest.yml`; `cf push herald-bot` (internal route) + `cf push herald-ui` (public) + network policy.
4. Bind services; set env (Telegram token, default provider=openai (selected by the binding)); open Telegram ASG.
5. **Smoke test:** `/actuator/health` UP; `POST /api/chat` returns a GenAI completion (the PONG test); a memory write lands on the volume; a Telegram turn round-trips. **1 instance.**

### Day 2 — "feature parity minus desktop bits"
6. PDFBox/Tika PDF extraction; verify Telegram doc handling.
7. MeetingNotes webhook end-to-end into Postgres; backstop disabled.
8. Morning-briefing cron fires once (weather optional); approvals + model-switch via UI proxy over the internal route.
9. Decide Google track (disable vs. start the Java-client rewrite).
10. Harden: CredHub for secrets, health-check tuning, memory sizing, log review.

---

## 12. Decisions needed from you

1. **Google Workspace:** disable for v1 (fast) or start the Java-client + refresh-token rewrite now (the big work item)?
2. **Storage for the Markdown vault:** **NFS volume** (1-day path, keeps the file model — recommended) vs. object-store abstraction (cleaner, bigger refactor)?
3. **UI:** keep two apps + internal route (recommended) or collapse UI into the bot (one app, but UI inherits Spring AI)?
4. **DB:** one shared Postgres for bot+UI (recommended) or two?
5. **GenAI model:** which chat model + embeddings model will the tile serve (drives default provider/model + whether vault RAG can light up)?
6. **Scale:** confirm **1 instance** for v1 (lets us skip all of §8). Any HA requirement?

---

## Appendix A — Feature → CF disposition (one-screen summary)

| Feature | Today | CF disposition | Severity |
|---|---|---|---|
| Chat / agent loop | Spring AI, OpenAI-compat | **Bind GenAI** | ✅ easy |
| Relational data | SQLite `~/.herald/herald.db` | **Postgres binding** | 🟠 medium |
| Markdown memory vault | local dir (+Obsidian) | **NFS volume**, drop Obsidian | 🟠 medium |
| Uploads | hardcoded `~/.herald/uploads` | **env var + volume** (code patch) | 🟠 blocker (small) |
| Skills / prompts | local dir + classpath | **classpath** (+ volume for user skills) | ✅ easy |
| Local model (LM Studio/Ollama) | localhost | **GenAI tile** | ✅ easy |
| Telegram | long-poll, 1 consumer | keep poll @1 inst + ASG; webhook to scale | 🟠 medium |
| Cron / briefings | per-JVM scheduler | OK @1 inst; ShedLock to scale | 🟠 medium |
| Google Workspace | `gws` CLI + Keychain + browser OAuth | disable v1; Java client + CredHub v2 | 🔴 high |
| Voice (whisper) | local CLI | degrade v1; hosted STT v2 | 🟡 low |
| PDF (pdftotext) | local CLI | **PDFBox/Tika** | 🟡 low |
| markitdown / gh skills | via shell tool | gone with shell tool | 🟡 low |
| `shell` tool | `sh -c` to agent | **disable on cloud** | 🔴 security |
| Apple Reminders | macOS CLI | disable (OS-gated) | 🟡 low |
| Obsidian search | macOS app | remove | 🟡 low |
| MeetingNotes backstop | desktop SQLite/files | disable; keep webhook | 🟠 medium |
| MCP client | off by default | no change | ✅ |
| A2A subagents | client-only, HTTP | works (egress) | ✅ |
| Secrets | `.env` | env / bindings / CredHub | 🟠 medium |
| Logging | stdout | CF-ready | ✅ |
| Ports | 8081/8080 hardcoded-ish | `$PORT` + internal route | ✅ easy |

*Line references in this doc come from a code audit on 2026-06-23; treat them as orientation and re-confirm against `main` when editing.*
