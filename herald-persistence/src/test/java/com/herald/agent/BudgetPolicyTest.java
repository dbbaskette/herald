package com.herald.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetPolicyTest {

    private JdbcTemplate jdbcTemplate;
    private UsageTracker usageTracker;
    private BudgetPolicy policy;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        usageTracker = mock(UsageTracker.class);
        when(usageTracker.estimateDailyCost()).thenReturn(BigDecimal.ZERO);
        when(usageTracker.estimateMonthlyCost()).thenReturn(BigDecimal.ZERO);
        policy = new BudgetPolicy(jdbcTemplate, usageTracker);
    }

    @Test
    void evaluateAllowsWhenNoRailsConfigured() {
        // All settings return empty lists
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of());

        var decision = policy.evaluate();

        assertThat(decision.verdict()).isEqualTo(BudgetPolicy.Verdict.ALLOW);
    }

    @Test
    void evaluateBlocksWhenPaused() {
        Instant future = Instant.now().plusSeconds(3600);
        stubSetting("budget.paused-until", future.toString());

        var decision = policy.evaluate();

        assertThat(decision.isBlocked()).isTrue();
        assertThat(decision.message()).contains("Paused");
    }

    @Test
    void evaluateIgnoresPausedUntilInPast() {
        stubSetting("budget.paused-until", Instant.now().minusSeconds(3600).toString());

        var decision = policy.evaluate();

        assertThat(decision.verdict()).isEqualTo(BudgetPolicy.Verdict.ALLOW);
    }

    @Test
    void evaluateBlocksWhenDailyCapReached() {
        stubSetting("budget.daily.usd", "5.00");
        when(usageTracker.estimateDailyCost()).thenReturn(new BigDecimal("5.00"));

        var decision = policy.evaluate();

        assertThat(decision.isBlocked()).isTrue();
        assertThat(decision.message()).contains("Daily budget");
    }

    @Test
    void evaluateBlocksWhenMonthlyCapReached() {
        stubSetting("budget.monthly.usd", "100.00");
        when(usageTracker.estimateMonthlyCost()).thenReturn(new BigDecimal("100.00"));

        var decision = policy.evaluate();

        assertThat(decision.isBlocked()).isTrue();
        assertThat(decision.message()).contains("Monthly");
    }

    @Test
    void evaluateWarnsAt80PercentOfDaily() {
        stubSetting("budget.daily.usd", "5.00");
        when(usageTracker.estimateDailyCost()).thenReturn(new BigDecimal("4.00"));

        var decision = policy.evaluate();

        assertThat(decision.verdict()).isEqualTo(BudgetPolicy.Verdict.WARN);
        verify(jdbcTemplate).update(anyString(), eq("budget.warned-on"),
                eq(LocalDate.now(ZoneOffset.UTC).toString()));
    }

    @Test
    void evaluateDoesNotWarnTwiceInSameDay() {
        stubSetting("budget.daily.usd", "5.00");
        stubSetting("budget.warned-on", LocalDate.now(ZoneOffset.UTC).toString());
        when(usageTracker.estimateDailyCost()).thenReturn(new BigDecimal("4.00"));

        var decision = policy.evaluate();

        assertThat(decision.verdict()).isEqualTo(BudgetPolicy.Verdict.ALLOW);
    }

    @Test
    void tierOfKnownModels() {
        assertThat(BudgetPolicy.tierOf("claude-haiku-4-5")).isEqualTo(1);
        assertThat(BudgetPolicy.tierOf("claude-sonnet-4-5")).isEqualTo(2);
        assertThat(BudgetPolicy.tierOf("claude-opus-4-5")).isEqualTo(3);
        assertThat(BudgetPolicy.tierOf("gpt-4o-mini")).isEqualTo(1);
        assertThat(BudgetPolicy.tierOf("gpt-4o")).isEqualTo(2);
        assertThat(BudgetPolicy.tierOf("o1")).isEqualTo(3);
    }

    @Test
    void checkModelSwitchBlocksAboveCeiling() {
        stubSetting("budget.model-ceiling", "sonnet");

        String result = policy.checkModelSwitch("anthropic", "claude-opus-4-5");
        assertThat(result).isNotNull().contains("above the budget ceiling");
    }

    @Test
    void checkModelSwitchAllowsEqualOrBelowCeiling() {
        stubSetting("budget.model-ceiling", "sonnet");

        assertThat(policy.checkModelSwitch("anthropic", "claude-sonnet-4-5")).isNull();
        assertThat(policy.checkModelSwitch("anthropic", "claude-haiku-4-5")).isNull();
    }

    @Test
    void checkModelSwitchNullWhenNoCeiling() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of());
        assertThat(policy.checkModelSwitch("anthropic", "claude-opus-4-5")).isNull();
    }

    @Test
    void setModelCeilingOffDeletesSetting() {
        policy.setModelCeiling("off");
        verify(jdbcTemplate).update("DELETE FROM settings WHERE key = ?",
                "budget.model-ceiling");
    }

    @Test
    void pauseUntilNullPausesFor30Days() {
        policy.pauseUntil(null);
        verify(jdbcTemplate).update(anyString(), eq("budget.paused-until"),
                org.mockito.ArgumentMatchers.argThat(v -> {
                    if (!(v instanceof String s)) return false;
                    Instant parsed = Instant.parse(s);
                    long secondsFromNow = parsed.getEpochSecond() - Instant.now().getEpochSecond();
                    return secondsFromNow > 29L * 24 * 3600 && secondsFromNow < 31L * 24 * 3600;
                }));
    }

    private void stubSetting(String key, String value) {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(key)))
                .thenReturn(List.of(value));
    }
}
