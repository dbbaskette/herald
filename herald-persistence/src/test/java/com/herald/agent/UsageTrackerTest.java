package com.herald.agent;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UsageTrackerTest {

    private JdbcTemplate jdbcTemplate;
    private UsageTracker usageTracker;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        usageTracker = new UsageTracker(jdbcTemplate);
    }

    @Test
    void getDailyUsageReturnsSummary() {
        when(jdbcTemplate.queryForObject(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(new UsageTracker.UsageSummary(5000, 1000));

        UsageTracker.UsageSummary summary = usageTracker.getDailyUsage();

        assertThat(summary.tokensIn()).isEqualTo(5000);
        assertThat(summary.tokensOut()).isEqualTo(1000);
    }

    @SuppressWarnings("unchecked")
    @Test
    void getDailyUsageByAgentReturnsBreakdown() {
        List<UsageTracker.AgentUsage> expected = List.of(
                new UsageTracker.AgentUsage("main", "anthropic", "claude-sonnet-4-5", 3000, 600),
                new UsageTracker.AgentUsage("research-agent", "anthropic", "claude-haiku-4-5", 2000, 400));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(expected);

        List<UsageTracker.AgentUsage> result = usageTracker.getDailyUsageByAgent();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).agent()).isEqualTo("main");
        assertThat(result.get(1).agent()).isEqualTo("research-agent");
    }

    @SuppressWarnings("unchecked")
    @Test
    void estimateDailyCostCalculatesFromPricing() {
        // 1M tokens in + 1M tokens out at claude-sonnet-4-5 pricing ($3/$15 per M)
        List<UsageTracker.AgentUsage> usage = List.of(
                new UsageTracker.AgentUsage("main", "anthropic", "claude-sonnet-4-5", 1_000_000, 1_000_000));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(usage);

        BigDecimal cost = usageTracker.estimateDailyCost();

        // $3 input + $15 output = $18
        assertThat(cost).isEqualByComparingTo(new BigDecimal("18.0000"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void estimateDailyCostReturnsZeroForUnknownModel() {
        List<UsageTracker.AgentUsage> usage = List.of(
                new UsageTracker.AgentUsage("main", "ollama", "llama3.2", 500_000, 100_000));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(usage);

        BigDecimal cost = usageTracker.estimateDailyCost();

        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @SuppressWarnings("unchecked")
    @Test
    void estimateDailyCostWithNoUsage() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        BigDecimal cost = usageTracker.estimateDailyCost();

        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
