# model_usage table grows unboundedly — no retention or cleanup

## Summary

Every agent turn inserts a row into the `model_usage` table via `AgentMetrics.recordTurn()`. There is no retention policy, cleanup job, or partition scheme. Over time this table will grow without bound, degrading SQLite query performance (particularly the `GROUP BY` aggregations in `UsageTracker`) and consuming disk space in the `~/.herald/herald.db` file.

## Current State

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
```

- `AgentMetrics.recordTurn()` inserts on every turn
- `UsageTracker.getDailyUsage()` queries with `WHERE date(timestamp) = date('now')` — full table scan with no index on `timestamp`
- No scheduled cleanup or `DELETE WHERE timestamp < ...` anywhere in the codebase

## Impact

- A moderately active bot (~100 turns/day) accumulates ~36,500 rows/year
- SQLite `GROUP BY` aggregations over the full table slow down proportionally
- `~/.herald/herald.db` file size grows indefinitely
- No way for the user to prune old data without manual SQL

## Proposed Fix

1. **Add an index**: `CREATE INDEX idx_model_usage_timestamp ON model_usage(timestamp)` to speed up date-filtered queries
2. **Add a retention job**: `@Scheduled` task that deletes rows older than N days (configurable, default 90)
3. **Add a `/usage purge [days]` command** or expose via `/memory`-style admin command
4. **Consider monthly rollup**: Aggregate old per-turn rows into daily summaries before deleting detail

## Tasks

- [ ] Add timestamp index to `schema.sql`
- [ ] Implement scheduled retention job (e.g., `@Scheduled(cron = "0 0 3 * * *")`)
- [ ] Make retention period configurable via `herald.agent.usage-retention-days`
- [ ] Add `/usage` admin command for manual purge
- [ ] Add test for retention job

## References

- `herald-bot/src/main/resources/schema.sql`
- `herald-bot/src/main/java/com/herald/agent/AgentMetrics.java`
- `herald-bot/src/main/java/com/herald/agent/UsageTracker.java`
