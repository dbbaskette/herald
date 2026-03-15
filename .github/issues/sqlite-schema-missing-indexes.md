# SQLite schema lacks indexes — queries will degrade as tables grow

## Summary

The `schema.sql` file creates 6 tables with no secondary indexes. Several queries in `UsageTracker` and `MemoryRepository` filter on columns like `timestamp`, `conversation_id`, and `key` using full table scans. As the tables grow, query performance will degrade.

## Current Schema (no indexes)

```sql
CREATE TABLE IF NOT EXISTS model_usage (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL,
    provider TEXT,
    model TEXT,
    input_tokens INTEGER,
    output_tokens INTEGER,
    conversation_id TEXT
);

CREATE TABLE IF NOT EXISTS memory (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS model_overrides (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    updated_at TEXT DEFAULT (datetime('now'))
);
```

## Queries That Would Benefit from Indexes

1. `UsageTracker.getDailyUsage()`: `WHERE date(timestamp) = date('now')` — scans all rows
2. `UsageTracker.getDailyUsageByAgent()`: `WHERE date(timestamp) = date('now') GROUP BY model` — scans all rows
3. `MemoryRepository.get(key)`: Uses PRIMARY KEY (already indexed)
4. Spring AI `JdbcChatMemoryRepository` queries on `conversation_id`: needs index for efficient memory retrieval

## Proposed Indexes

```sql
CREATE INDEX IF NOT EXISTS idx_model_usage_timestamp ON model_usage(timestamp);
CREATE INDEX IF NOT EXISTS idx_model_usage_date ON model_usage(date(timestamp));
CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id);
```

Note: SQLite supports indexes on expressions (`date(timestamp)`) since 3.9.0, which would directly optimize the `date(timestamp) = date('now')` pattern.

## Tasks

- [ ] Add indexes to `schema.sql`
- [ ] Verify `messages` table is queried by `conversation_id` (Spring AI `JdbcChatMemoryRepository`)
- [ ] Run `EXPLAIN QUERY PLAN` on critical queries to verify index usage
- [ ] Consider adding `ANALYZE` as part of the retention job (see model-usage-table-unbounded-growth issue)

## References

- `herald-bot/src/main/resources/schema.sql`
- `herald-bot/src/main/java/com/herald/agent/UsageTracker.java`
- `herald-bot/src/main/java/com/herald/memory/MemoryRepository.java`
