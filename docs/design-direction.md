# Herald Console — design direction

> Established 2026-05. Pivoted v2 — same conversation. Updates land here first,
> code second.

## The brief

The Herald Console is a **control room**, not a marketing site. It serves a
single user who lives in Telegram all day, who already trusts Herald with their
memory, calendar, and meetings, and who occasionally drops into the Console to
peek at state, browse memory, edit a skill, or change a cron. The Console
should respect that — quiet, dense, monospaced, signal-not-noise.

The design tradition this draws from: htop, Linear, GitHub's modern issue
views, the JetBrains IDE inspectors, editorial newspaper layouts. The tradition
it avoids: card-on-card SaaS dashboards, purple gradients on white, soft
shadows, lift-on-hover animations, generic dashboard chrome.

## The one-line aesthetic

**Control-room editorial: monospaced body, serif for display, cool gray
canvas, saturated semantic color where it carries meaning, horizontal rules
over card chrome, glyphs over icons.**

## What changed in v2

The original direction (May, v1) committed to "ink + paper + gold-only." It
read as refined but **too restrained** — flat, hard to scan, no semantic color
to help the eye triage state.

v2 keeps the editorial discipline but:

- **Cool gray base** instead of warm paper. Reads more like a tool, less like a
  manuscript.
- **Larger type.** 14.5/22 body (up from 13/18); 30/36 display (up from 24/30).
- **Saturated semantic color.** Greens are green. Reds are red. Each color
  carries a specific meaning. **Color = information**, not decoration.
- Gold stays as the **brand** accent (sidebar active bar, primary buttons), but
  loses its "only chromatic" monopoly.

## Typography

| Role | Font | Why |
|---|---|---|
| Display (page titles, brand) | **IBM Plex Serif** | Editorial weight, distinctive in a tooling UI. |
| Body, labels, data, code | **JetBrains Mono** | Fixed-width grid for numerics; tool-feel. |

Type scale (px / line-height):

```
Display title    30 / 36   IBM Plex Serif       regular   tracking -0.01em
Section heading  15 / 22   JetBrains Mono       500       uppercase tracking 0.06em
Body / data      14.5 / 22 JetBrains Mono       400
Label            12 / 16   JetBrains Mono       500       uppercase tracking 0.08em
Caption          12 / 16   JetBrains Mono       400       muted
Glyph            15 / 15   JetBrains Mono       400
```

## Palette

Cool grays (Tailwind zinc-flavored) + a confident chromatic set with assigned
meaning.

```
# Canvas
--ink           #18181b    primary text
--ink-2         #27272a    darker borders / inverted-region bg
--paper         #fafafa    page background
--paper-2       #f4f4f5    raised surface (subtle)
--paper-3       #e4e4e7    rules, dividers
--graphite      #52525b    secondary text
--graphite-2    #71717a    muted text, captions
--graphite-3    #a1a1aa    very-muted, scrollbars

# Brand
--gold          #c8a55a    sidebar active bar, primary button, "Herald" wordmark
--gold-dim      #a68a3e    gold hover / pressed
--gold-soft     #faf5e6    gold-tinted background for highlights

# Semantic — confident, saturated; each carries a specific meaning
--ok            #16a34a    success, live, healthy            (green-600)
--ok-soft       #dcfce7    ok background
--warn          #d97706    warning, attention                (amber-600)
--warn-soft     #fef3c7    warn background
--err           #dc2626    error, dead, failure              (red-600)
--err-soft      #fee2e2    err background
--info          #2563eb    info, live-pulse, links           (blue-600)
--info-soft     #dbeafe    info background
--data          #0891b2    memory, data, technical           (cyan-600)
--data-soft     #cffafe    data background
--magic         #9333ea    subagents, AI-special, magic      (purple-600)
--magic-soft    #f3e8ff    magic background

# Dark sidebar
--sidebar       #18181b
--sidebar-hover #27272a
--sidebar-active #3f3f46
--sidebar-text  #a1a1aa
--sidebar-text-active  #fafafa
```

**Rule for color use:** every saturated color in the UI **must mean something**.
Don't decorate with color. Status, type, source, kind — those are the only
reasons. If a tag is purple, it's a subagent. If a number is green, it's healthy.
If a path is blue, it's clickable.

## Status glyphs

| Glyph | Kind | Color | Meaning |
|---|---|---|---|
| ●  | live      | `--ok`     | running, connected, healthy |
| ●  | live-pulse | `--info` (pulse) | live data streaming |
| ○  | idle      | `--graphite-2` | not running, not connected |
| ↑  | running   | `--ok`     | actively executing |
| ◐  | warn      | `--warn`   | partial / degraded |
| ✕  | err       | `--err`    | failed, stopped, error |
| ─  | na        | `--graphite-3` | not applicable |
| ▴  | attention | `--warn`   | needs review |
| ●  | gold      | `--gold`   | brand-tagged item |
| ●  | magic     | `--magic`  | subagent / AI-special |

## Color-by-meaning assignments

These are the specific places semantic color shows up:

| Surface | Use of color |
|---|---|
| Sidebar active item | Gold left-bar + light text |
| NowStripe live dot | Blue (`--info`), pulsing; red `✕` if bot is down |
| Status page Bot section | Green `●` if running, red `✕` if stopped |
| Memory type pill — `concept` | Blue tint |
| Memory type pill — `entity` | Purple tint |
| Memory type pill — `source` | Cyan tint |
| Memory type pill — `user`, `feedback`, `project`, `reference` | Gray tints |
| History tool-call border | Gold left-bar |
| History subagent-call border | Purple left-bar |
| Cron status — `success` | Green |
| Cron status — `running` | Blue, animated |
| Cron status — `failed`/`error` | Red |
| Skills SSE chip — `connected` | Blue pulse |
| Skills SSE chip — `error` | Red |
| Approval inbox row | Amber left-bar (attention required) |

## Layout vocabulary (unchanged from v1)

### Rules over cards
Replace `border + rounded-lg + box-shadow` cards with **labeled horizontal
rules**.

### NowStripe (always visible)
A single mono line at the top of every page showing system heartbeat.

```
●  sonnet-4-5  ·  up 2d 14h  ·  $0.87 today  ·  ─ alerts
```

### Page header
Big serif title (IBM Plex Serif 30px) on the left, mono path on the right, 1px
divider rule beneath.

### Density (slightly looser than v1)

- Body: **14.5px / 22px** line (up from 13/18). Easier to read at sustained
  attention.
- Page padding: 28px top, 36px sides.
- Section gap: 36px (clear breath between sections).
- Inline gap inside sections: 8px (still tight; terminal-feeling).

### Hover states

| Element | Hover |
|---|---|
| Sidebar nav item | bg shift to `--sidebar-hover`, gold left-bar on active |
| Inline link | underline from left, no color change |
| Button | invert ink ↔ paper, no shadow |
| Row | bg shift to `--paper-2`, no border, no lift |

Speed: 100ms transitions (up slightly from 80ms — feels less jittery at the
new type sizes).

### Animations

**One** animation in steady-state: the live-data dot pulses at `--info` blue
(1.6s breathing). Everything else is instant.

## Component vocabulary (unchanged from v1)

`<NowStripe>` · `<PageHeader>` · `<SectionRule>` · `<MetricRow>` ·
`<StatusGlyph>` · `<DataTable>`

## Sidebar (slightly cooler grays now)

- Width 200px. Tighter.
- Brand: gold "H" mark + "Herald" in IBM Plex Serif.
- Items: glyph + label, all monospace, **14.5px** now.
- Active item: gold 2px left-bar + bold mono, no background change.
- Footer: version pinned to bottom in 11px mono, opacity 0.4.

## What's still out of scope (today)

- Dark mode. v2 is light. Dark mode is a separate pass once light is dialed.
- Charts / sparkline visualizations.
- Custom illustrations / mascot work.
- Mobile responsive polish.

## Done criteria for each page (still apply)

- No card chrome (no `border-radius > 4px`, no `box-shadow`).
- All numerics, timestamps, IDs in JetBrains Mono.
- All page titles in IBM Plex Serif.
- All status indicators use `<StatusGlyph>`, not raw color dots.
- All sections use `<SectionRule>`, not `<div class="card">`.
- **Color carries meaning** — every saturated color in the page should map to
  a row in the Color-by-meaning table above.
