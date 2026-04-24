package com.herald.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Self-imposed spending rails for Herald (#319). Tracks daily and monthly
 * budget caps plus an optional model-tier ceiling, all stored in the
 * {@code settings} table so they survive restarts. Queried pre-turn by
 * {@code TelegramPoller} and pre-cron by {@code CronService}.
 *
 * <p>Also records which warning threshold has been fired today so the user
 * only gets one "80% of budget" notification per day.</p>
 *
 * <p>All dollar amounts stored as plain decimal strings (e.g. "5.00").
 * Quiet / noop when no rails are configured.</p>
 */
@Service
@ConditionalOnBean(JdbcTemplate.class)
public class BudgetPolicy {

    private static final Logger log = LoggerFactory.getLogger(BudgetPolicy.class);
    private static final String KEY_DAILY = "budget.daily.usd";
    private static final String KEY_MONTHLY = "budget.monthly.usd";
    private static final String KEY_MODEL_CEILING = "budget.model-ceiling";
    private static final String KEY_PAUSED_UNTIL = "budget.paused-until";
    private static final String KEY_WARNED_ON = "budget.warned-on";  // YYYY-MM-DD when 80% warning fired

    private final JdbcTemplate jdbcTemplate;
    private final UsageTracker usageTracker;

    public BudgetPolicy(JdbcTemplate jdbcTemplate, UsageTracker usageTracker) {
        this.jdbcTemplate = jdbcTemplate;
        this.usageTracker = usageTracker;
    }

    // --- Decision API ---

    /**
     * Evaluate whether a new turn may proceed. Called before every dispatch
     * in {@code TelegramPoller} and before every cron-driven prompt.
     */
    public Decision evaluate() {
        BudgetSettings s = load();
        if (s.isEmpty()) {
            return Decision.allow();
        }

        Instant now = Instant.now();
        if (s.pausedUntil != null && now.isBefore(s.pausedUntil)) {
            return Decision.block("Paused until " + s.pausedUntil + ". Use /budget resume to override.");
        }

        BigDecimal daily = usageTracker.estimateDailyCost();
        BigDecimal monthly = usageTracker.estimateMonthlyCost();

        if (s.dailyCap != null && daily.compareTo(s.dailyCap) >= 0) {
            return Decision.block(String.format(
                    "Daily budget reached ($%.2f of $%.2f). /budget resume to override, "
                            + "or wait for 00:00 UTC.",
                    daily.doubleValue(), s.dailyCap.doubleValue()));
        }
        if (s.monthlyCap != null && monthly.compareTo(s.monthlyCap) >= 0) {
            return Decision.block(String.format(
                    "Monthly budget reached ($%.2f of $%.2f). Raise the cap or wait for "
                            + "next month.",
                    monthly.doubleValue(), s.monthlyCap.doubleValue()));
        }

        // Warning threshold: 80% of daily. Fire at most once per day.
        if (s.dailyCap != null) {
            BigDecimal threshold = s.dailyCap.multiply(BigDecimal.valueOf(0.80));
            if (daily.compareTo(threshold) >= 0 && !s.warnedToday()) {
                markWarnedToday();
                return Decision.warn(String.format(
                        "%.0f%% of today's budget reached ($%.2f of $%.2f).",
                        daily.doubleValue() / s.dailyCap.doubleValue() * 100.0,
                        daily.doubleValue(), s.dailyCap.doubleValue()));
            }
        }

        return Decision.allow();
    }

    // --- Model-ceiling check ---

    /**
     * Check whether switching the main agent to the given model is allowed
     * under the current ceiling. Returns a message when blocked, null when OK.
     */
    public String checkModelSwitch(String provider, String model) {
        BudgetSettings s = load();
        if (s.modelCeiling == null) {
            return null;
        }
        int requestedTier = tierOf(model);
        int ceilingTier = tierOf(s.modelCeiling);
        if (requestedTier > ceilingTier) {
            return String.format(
                    "Model '%s' is above the budget ceiling '%s'. "
                            + "Raise the ceiling with /budget model-ceiling off to allow.",
                    model, s.modelCeiling);
        }
        return null;
    }

    static int tierOf(String model) {
        if (model == null) return 0;
        String lower = model.toLowerCase(Locale.ROOT);
        if (lower.contains("haiku")) return 1;
        if (lower.contains("sonnet")) return 2;
        if (lower.contains("opus")) return 3;
        if (lower.startsWith("gpt-4o-mini")) return 1;
        if (lower.startsWith("gpt-4o")) return 2;
        if (lower.startsWith("o1")) return 3;
        if (lower.contains("flash")) return 1;
        return 2;
    }

    // --- Mutation API (exposed via /budget) ---

    public void setDailyCap(BigDecimal usd) {
        writeSetting(KEY_DAILY, usd.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    public void setMonthlyCap(BigDecimal usd) {
        writeSetting(KEY_MONTHLY, usd.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    public void setModelCeiling(String tier) {
        if (tier == null || tier.isBlank() || tier.equalsIgnoreCase("off")) {
            deleteSetting(KEY_MODEL_CEILING);
        } else {
            writeSetting(KEY_MODEL_CEILING, tier.toLowerCase(Locale.ROOT));
        }
    }

    public void clear(String field) {
        switch (field.toLowerCase(Locale.ROOT)) {
            case "daily" -> deleteSetting(KEY_DAILY);
            case "monthly" -> deleteSetting(KEY_MONTHLY);
            case "ceiling", "model-ceiling" -> deleteSetting(KEY_MODEL_CEILING);
            case "all" -> {
                deleteSetting(KEY_DAILY);
                deleteSetting(KEY_MONTHLY);
                deleteSetting(KEY_MODEL_CEILING);
                deleteSetting(KEY_PAUSED_UNTIL);
            }
            default -> throw new IllegalArgumentException("Unknown budget field: " + field);
        }
    }

    /**
     * Pause until the given instant (UTC). Use {@code null} to pause
     * indefinitely (`/budget pause`); use past time to effectively resume.
     */
    public void pauseUntil(Instant until) {
        if (until == null) {
            // "Indefinite" = pause for 30 days; user can resume any time.
            until = Instant.now().plusSeconds(30L * 24 * 3600);
        }
        writeSetting(KEY_PAUSED_UNTIL, until.toString());
    }

    public void resume() {
        deleteSetting(KEY_PAUSED_UNTIL);
    }

    // --- Query API ---

    public BudgetSettings current() {
        return load();
    }

    // --- Private helpers ---

    BudgetSettings load() {
        return new BudgetSettings(
                readDecimal(KEY_DAILY),
                readDecimal(KEY_MONTHLY),
                readSetting(KEY_MODEL_CEILING),
                readInstant(KEY_PAUSED_UNTIL),
                readSetting(KEY_WARNED_ON));
    }

    private void markWarnedToday() {
        writeSetting(KEY_WARNED_ON, LocalDate.now(ZoneOffset.UTC).toString());
    }

    private BigDecimal readDecimal(String key) {
        String raw = readSetting(key);
        if (raw == null) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            log.warn("Malformed budget setting '{}'='{}' — ignoring", key, raw);
            return null;
        }
    }

    private Instant readInstant(String key) {
        String raw = readSetting(key);
        if (raw == null) return null;
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            log.warn("Malformed budget setting '{}'='{}' — ignoring", key, raw);
            return null;
        }
    }

    private String readSetting(String key) {
        try {
            List<String> values = jdbcTemplate.queryForList(
                    "SELECT value FROM settings WHERE key = ?", String.class, key);
            return values.isEmpty() ? null : values.get(0);
        } catch (Exception e) {
            log.debug("Could not read setting '{}': {}", key, e.getMessage());
            return null;
        }
    }

    private void writeSetting(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO settings (key, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, "
                        + "updated_at = CURRENT_TIMESTAMP",
                key, value);
    }

    private void deleteSetting(String key) {
        jdbcTemplate.update("DELETE FROM settings WHERE key = ?", key);
    }

    // --- Records ---

    public record BudgetSettings(
            BigDecimal dailyCap,
            BigDecimal monthlyCap,
            String modelCeiling,
            Instant pausedUntil,
            String warnedOnYmd) {

        public boolean isEmpty() {
            return dailyCap == null && monthlyCap == null
                    && modelCeiling == null && pausedUntil == null;
        }

        public boolean warnedToday() {
            return warnedOnYmd != null
                    && warnedOnYmd.equals(LocalDate.now(ZoneOffset.UTC).toString());
        }
    }

    public enum Verdict { ALLOW, WARN, BLOCK }

    public record Decision(Verdict verdict, String message) {
        public static Decision allow() { return new Decision(Verdict.ALLOW, null); }
        public static Decision warn(String msg) { return new Decision(Verdict.WARN, msg); }
        public static Decision block(String msg) { return new Decision(Verdict.BLOCK, msg); }
        public boolean isBlocked() { return verdict == Verdict.BLOCK; }
    }
}
