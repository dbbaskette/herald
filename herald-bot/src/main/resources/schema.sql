CREATE TABLE IF NOT EXISTS messages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    role        TEXT NOT NULL,
    content     TEXT,
    tool_calls  TEXT,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS memory (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    key        TEXT UNIQUE NOT NULL,
    value      TEXT NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS cron_jobs (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    name      TEXT UNIQUE NOT NULL,
    schedule  TEXT NOT NULL,
    prompt    TEXT NOT NULL,
    last_run  DATETIME,
    enabled   INTEGER DEFAULT 1,
    built_in  INTEGER DEFAULT 0
);

INSERT OR IGNORE INTO cron_jobs (name, schedule, prompt, enabled, built_in) VALUES (
    'morning-briefing',
    '0 0 7 * * 1-5',
    'You are running as a scheduled morning briefing. Compile a concise morning digest for the user:

1. **Weather** — Fetch the current weather and today''s forecast from wttr.in for the user''s configured location.
2. **Calendar** — If calendar tools are available, list today''s events and meetings with times.
3. **Top priorities** — Surface the top 3 priorities or open items from memory.
4. **Flagged emails** — If email tools are available, check for any flagged or important unread emails and summarize them.
5. **Things you''d want to know today** — Add an adaptive section with anything else relevant: upcoming deadlines, reminders, or notable context from recent conversations.

Keep the tone friendly and concise. Use bullet points and bold headers for readability.',
    1, 1
);

INSERT OR IGNORE INTO cron_jobs (name, schedule, prompt, enabled, built_in) VALUES (
    'weekly-review',
    '0 0 18 * * 5',
    'You are running as a scheduled weekly review. Compile a concise end-of-week summary for the user:

1. **Week recap** — Summarize the key conversations, tasks, and activity from this week.
2. **Open items** — Surface any unresolved tasks, pending questions, or open items from memory.
3. **Next week preview** — If calendar tools are available, preview next week''s scheduled events and commitments.
4. **Suggestions** — Offer any recommendations for follow-ups or preparation for the coming week.

Keep the tone reflective and actionable. Use bullet points and bold headers for readability.',
    1, 1
);

CREATE TABLE IF NOT EXISTS commands (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    type         TEXT NOT NULL,
    payload      TEXT,
    status       TEXT DEFAULT 'pending',
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME
);

CREATE TABLE IF NOT EXISTS model_usage (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    subagent_id  TEXT,
    provider     TEXT NOT NULL,
    model        TEXT NOT NULL,
    tokens_in    INTEGER,
    tokens_out   INTEGER,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Spring AI JDBC chat memory (must match spring-ai's expected schema)
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id TEXT NOT NULL,
    content         TEXT NOT NULL,
    type            TEXT NOT NULL,
    timestamp       DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS model_overrides (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    provider   TEXT NOT NULL,
    model      TEXT NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS settings (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

INSERT OR IGNORE INTO settings (key, value) VALUES ('obsidian.vault-path', '');
INSERT OR IGNORE INTO settings (key, value) VALUES ('weather.location', '');
INSERT OR IGNORE INTO settings (key, value) VALUES ('agent.persona', 'Herald');
INSERT OR IGNORE INTO settings (key, value) VALUES ('cron.timezone', 'America/New_York');
INSERT OR IGNORE INTO settings (key, value) VALUES ('agent.max-context-tokens', '200000');
