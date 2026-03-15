# Add custom health indicators for Telegram, SQLite, and GWS

## Summary

Herald exposes Spring Boot Actuator health endpoints but relies only on the default auto-configured indicators. There are no custom health checks for Herald's critical dependencies: Telegram Bot API connectivity, SQLite database health, and GWS CLI availability. When these services are degraded, the `/health` endpoint still reports UP.

## Current State

- `application.yaml` exposes `health` and `info` actuator endpoints
- No custom `HealthIndicator` beans exist in the codebase
- `GwsAvailabilityChecker` runs once at `@PostConstruct` but doesn't contribute to health
- Telegram connectivity is only tested when polling (silent failure)
- SQLite is checked implicitly by Spring's `DataSourceHealthIndicator`, but WAL mode issues or disk full conditions may not be caught

## Proposed Health Indicators

### 1. TelegramHealthIndicator
```java
@Component
@ConditionalOnProperty("herald.telegram.bot-token")
public class TelegramHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Call getMe() API to verify bot token and connectivity
    }
}
```

### 2. GwsHealthIndicator
```java
@Component
public class GwsHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Check gwsAvailable flag and optionally re-verify
        // Report version in health details
    }
}
```

### 3. SQLiteHealthIndicator (enhanced)
```java
@Component
public class SQLiteHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Run PRAGMA integrity_check (lightweight)
        // Check WAL file size
        // Check available disk space
    }
}
```

## Tasks

- [ ] Create `TelegramHealthIndicator` with `getMe()` check (with caching to avoid rate limits)
- [ ] Create `GwsHealthIndicator` wrapping `GwsAvailabilityChecker`
- [ ] Create `SQLiteHealthIndicator` with WAL and disk space checks
- [ ] Wire all as `@ConditionalOnProperty` where appropriate
- [ ] Update `/status` Telegram command to include health summary
- [ ] Add tests for each indicator

## References

- `herald-bot/src/main/java/com/herald/tools/GwsAvailabilityChecker.java`
- `herald-bot/src/main/java/com/herald/telegram/TelegramBotConfig.java`
- `herald-bot/src/main/java/com/herald/config/DataSourceConfig.java`
- `herald-bot/src/main/resources/application.yaml`
