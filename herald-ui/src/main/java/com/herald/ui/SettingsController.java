package com.herald.ui;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
class SettingsController {
    static final int MIN_CONTEXT_TOKENS = 1;
    static final int MAX_CONTEXT_TOKENS = 2_000_000;
    static final List<String> KEYS = List.of("agent.persona", "agent.max-context-tokens", "cron.timezone",
            "obsidian.vault-path", "weather.location");
    record SettingStatus(String saved, String effective, String source, String environmentVariable,
                         String application, boolean restartRequired) {}
    record SettingsResult(boolean success, Map<String, String> savedSettings,
                          Map<String, SettingStatus> settings, Map<String, String> validationErrors,
                          String error, String message) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RuntimeSettingsClient runtime;
    SettingsController(JdbcTemplate jdbc, PlatformTransactionManager manager, RuntimeSettingsClient runtime) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.runtime = runtime;
    }
    private Map<String, String> saved() {
        var values = new LinkedHashMap<String, String>();
        jdbc.query("SELECT key, value FROM settings ORDER BY key", rs -> {
            if (KEYS.contains(rs.getString("key"))) values.put(rs.getString("key"), rs.getString("value"));
        });
        return values;
    }
    private SettingsResult result(Map<String, String> saved) {
        Map<String, RuntimeSettingsClient.EffectiveSetting> effective;
        try { effective = runtime.snapshot(); }
        catch (RuntimeException unavailable) { effective = Map.of(); }
        var statuses = new LinkedHashMap<String, SettingStatus>();
        for (String key : KEYS) {
            var live = effective.get(key);
            String value = saved.getOrDefault(key, "");
            boolean known = live != null && live.effective() != null && live.source() != null;
            boolean matches = known && value.equals(live.effective());
            statuses.put(key, new SettingStatus(value, known ? live.effective() : null,
                    known ? live.source() : "unknown", known ? live.environmentVariable() : null,
                    !known ? "unknown" : matches ? "matches-runtime" : "configuration-managed", known && !matches));
        }
        return new SettingsResult(true, saved, statuses, Map.of(), null,
                "Preferences saved in SQLite. Runtime values come from bot startup configuration; saving does not apply them.");
    }
    @GetMapping
    ResponseEntity<SettingsResult> getAll() {
        try { return ResponseEntity.ok(result(saved())); }
        catch (RuntimeException failure) { return failure("load-failed", "Could not load saved settings."); }
    }
    /** Flat update objects remain accepted; responses explicitly distinguish save and application. */
    @PutMapping
    ResponseEntity<SettingsResult> update(@RequestBody Map<String, String> updates) {
        var errors = validate(updates);
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(new SettingsResult(false, Map.of(), Map.of(),
                errors, "validation-failed", "Correct the highlighted values. No settings were saved."));
        final Map<String, String> saved;
        try {
            saved = transaction.execute(status -> {
                for (var entry : updates.entrySet()) jdbc.update(
                        "INSERT INTO settings (key,value,updated_at) VALUES (?,?,CURRENT_TIMESTAMP) "
                                + "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=CURRENT_TIMESTAMP",
                        entry.getKey(), entry.getValue());
                return saved();
            });
        } catch (DataAccessException failure) {
            return failure("save-database-failed", "Could not save settings. No changes were committed. Retry your draft.");
        } catch (RuntimeException failure) {
            return failure("save-failed", "Could not save settings. No changes were committed. Retry your draft.");
        }
        return ResponseEntity.ok(result(saved));
    }
    private ResponseEntity<SettingsResult> failure(String code, String message) {
        return ResponseEntity.internalServerError().body(new SettingsResult(false, Map.of(), Map.of(), Map.of(), code, message));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<SettingsResult> malformedRequest() {
        return ResponseEntity.badRequest().body(new SettingsResult(false, Map.of(), Map.of(),
                Map.of("_request", "Send an object of setting names and text values."), "validation-failed",
                "Invalid settings request. No settings were saved."));
    }
    static Map<String, String> validate(Map<String, String> updates) {
        var errors = new LinkedHashMap<String, String>();
        updates.forEach((key, value) -> {
            if (!KEYS.contains(key)) errors.put(key, "Unsupported setting.");
            else if (value == null) errors.put(key, "A text value is required.");
            else if (value.length() > 4096) errors.put(key, "Use at most 4096 characters.");
            else if (key.equals("cron.timezone")) {
                try { ZoneId.of(value); }
                catch (DateTimeException invalid) { errors.put(key, "Enter a valid timezone, for example America/New_York or UTC."); }
            } else if (key.equals("agent.max-context-tokens")) {
                try {
                    if (!value.matches("[0-9]+")) throw new NumberFormatException();
                    int tokens = Integer.parseInt(value);
                    if (tokens < MIN_CONTEXT_TOKENS || tokens > MAX_CONTEXT_TOKENS) throw new NumberFormatException();
                } catch (NumberFormatException invalid) { errors.put(key, "Enter a whole number from 1 to 2000000."); }
            }
        });
        return errors;
    }
}
