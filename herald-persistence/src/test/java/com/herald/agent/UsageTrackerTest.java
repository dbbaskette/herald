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

    // --- Phase 1 cache tracking (#313) ---

    @Test
    void usageSummaryCacheHitRatioHandlesZeroInput() {
        UsageTracker.UsageSummary empty = new UsageTracker.UsageSummary(0, 0, 0, 0);
        assertThat(empty.cacheHitRatio()).isEqualTo(0.0);
    }

    @Test
    void usageSummaryCacheHitRatioComputesFraction() {
        UsageTracker.UsageSummary summary = new UsageTracker.UsageSummary(1000, 500, 4000, 200);
        // ratio = cacheRead / tokensIn (uncached input)
        assertThat(summary.cacheHitRatio()).isEqualTo(4.0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void estimateDailyCostAppliesCacheMultipliers() {
        // Anthropic Sonnet: $3/M in, $15/M out, $0.30/M cache read (10%), $3.75/M cache write (125%)
        UsageTracker.AgentUsage usage = new UsageTracker.AgentUsage(
                "main", "anthropic", "claude-sonnet-4-5",
                /* tokensIn    */ 1_000_000,
                /* tokensOut   */   500_000,
                /* cacheRead   */ 4_000_000,
                /* cacheWrite  */   200_000);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of(usage));

        BigDecimal cost = usageTracker.estimateDailyCost();

        // Input: 1M × $3 = $3.00
        // Output: 0.5M × $15 = $7.50
        // Cache read: 4M × $3 × 0.10 = $1.20
        // Cache write: 0.2M × $3 × 1.25 = $0.75
        // Total: $12.45
        assertThat(cost).isEqualByComparingTo(new BigDecimal("12.4500"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void estimateDailyCostForNonAnthropicSkipsCachePricing() {
        UsageTracker.AgentUsage usage = new UsageTracker.AgentUsage(
                "main", "openai", "gpt-4o", 100_000, 50_000, 0, 0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of(usage));

        BigDecimal cost = usageTracker.estimateDailyCost();

        // 0.1M × $2.50 + 0.05M × $10 = $0.25 + $0.50 = $0.75
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.7500"));
    }
}
