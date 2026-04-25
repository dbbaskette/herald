package com.herald.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.BiPredicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fires the memory-consolidation reminder at most once per UTC day — the first
 * time the user converses after midnight. When fired, {@link HeraldAutoMemoryAdvisor}
 * injects a {@code <system-reminder>Consolidate the long-term memory by
 * summarizing and removing redundant information.</system-reminder>} block
 * into that turn's system prompt, so the model opportunistically cleans house.
 *
 * <p>State is persisted in the {@code settings} table under the key
 * {@value #KEY_LAST_FIRED}. A missing row or malformed value triggers on the
 * next turn (safe failure mode — at most one extra consolidation).</p>
 *
 * <p>No-ops when no {@link JdbcTemplate} is configured, so task-mode
 * (ephemeral agents) never fires consolidation. See issue #268.</p>
 */
public class DailyConsolidationTrigger implements BiPredicate<ChatClientRequest, Instant> {

    private static final Logger log = LoggerFactory.getLogger(DailyConsolidationTrigger.class);
    private static final String KEY_LAST_FIRED = "memory.consolidation.last-fired-ymd";

    private final JdbcTemplate jdbcTemplate;

    public DailyConsolidationTrigger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean test(ChatClientRequest request, Instant when) {
        if (jdbcTemplate == null || when == null) {
            return false;
        }
        String today = LocalDate.ofInstant(when, ZoneOffset.UTC).toString();
        try {
            String lastFired = readSetting();
            if (today.equals(lastFired)) {
                return false;
            }
            writeSetting(today);
            log.info("Firing daily memory-consolidation reminder (last fired: {})",
                    lastFired == null ? "never" : lastFired);
            return true;
        } catch (Exception e) {
            // Safe failure: skip this turn's reminder. Next turn will try again.
            log.warn("Failed to read/write consolidation trigger setting: {}", e.getMessage());
            return false;
        }
    }

    private String readSetting() {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT value FROM settings WHERE key = ?", String.class, KEY_LAST_FIRED);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void writeSetting(String value) {
        jdbcTemplate.update(
                "INSERT INTO settings (key, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, "
                        + "updated_at = CURRENT_TIMESTAMP",
                KEY_LAST_FIRED, value);
    }
}
