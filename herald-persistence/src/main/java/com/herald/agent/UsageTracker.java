package com.herald.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Queries the model_usage table to provide daily usage summaries and cost estimates.
 */
@Component
public class UsageTracker {

    // Cost per million tokens (input/output) for common models
    private static final Map<String, double[]> PRICING = Map.ofEntries(
            Map.entry("claude-sonnet-4-5", new double[]{3.0, 15.0}),
            Map.entry("claude-sonnet-4-5-20250514", new double[]{3.0, 15.0}),
            Map.entry("claude-haiku-4-5", new double[]{0.80, 4.0}),
            Map.entry("claude-haiku-4-5-20251001", new double[]{0.80, 4.0}),
            Map.entry("claude-opus-4-5", new double[]{15.0, 75.0}),
            Map.entry("claude-opus-4-5-20250520", new double[]{15.0, 75.0}),
            Map.entry("gpt-4o", new double[]{2.50, 10.0}),
            Map.entry("gpt-4o-mini", new double[]{0.15, 0.60}),
            Map.entry("o1", new double[]{15.0, 60.0}),
            Map.entry("o3-mini", new double[]{1.10, 4.40})
    );

    private static final double[] DEFAULT_PRICING = new double[]{0.0, 0.0};

    private final JdbcTemplate jdbcTemplate;

    public UsageTracker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Get total token usage for today, including Anthropic prompt-cache counters.
     */
    public UsageSummary getDailyUsage() {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(tokens_in), 0), COALESCE(SUM(tokens_out), 0), "
                        + "COALESCE(SUM(cache_read_tokens), 0), COALESCE(SUM(cache_write_tokens), 0) "
                        + "FROM model_usage WHERE date(created_at) = date('now')",
                (rs, rowNum) -> new UsageSummary(
                        rs.getLong(1), rs.getLong(2),
                        rs.getLong(3), rs.getLong(4)));
    }

    /**
     * Get per-agent breakdown of token usage for today.
     */
    public List<AgentUsage> getDailyUsageByAgent() {
        return jdbcTemplate.query(
                "SELECT COALESCE(subagent_id, 'main') as agent, provider, model, "
                        + "SUM(tokens_in) as total_in, SUM(tokens_out) as total_out, "
                        + "COALESCE(SUM(cache_read_tokens), 0) as cache_read, "
                        + "COALESCE(SUM(cache_write_tokens), 0) as cache_write "
                        + "FROM model_usage WHERE date(created_at) = date('now') "
                        + "GROUP BY subagent_id, provider, model ORDER BY total_in DESC",
                (rs, rowNum) -> new AgentUsage(
                        rs.getString("agent"),
                        rs.getString("provider"),
                        rs.getString("model"),
                        rs.getLong("total_in"),
                        rs.getLong("total_out"),
                        rs.getLong("cache_read"),
                        rs.getLong("cache_write")));
    }

    /**
     * Estimate total cost for today based on published pricing. For Anthropic,
     * applies the cache-pricing multipliers: cache reads are charged at 10% of
     * base input price, cache writes at 125%.
     */
    public BigDecimal estimateDailyCost() {
        List<AgentUsage> usageByAgent = getDailyUsageByAgent();
        BigDecimal totalCost = BigDecimal.ZERO;
        for (AgentUsage usage : usageByAgent) {
            double[] prices = PRICING.getOrDefault(usage.model(), DEFAULT_PRICING);
            double inputPrice = prices[0];
            double outputPrice = prices[1];
            double inputCost = (usage.tokensIn() / 1_000_000.0) * inputPrice;
            double outputCost = (usage.tokensOut() / 1_000_000.0) * outputPrice;
            double cacheReadCost = (usage.cacheReadTokens() / 1_000_000.0) * inputPrice * 0.10;
            double cacheWriteCost = (usage.cacheWriteTokens() / 1_000_000.0) * inputPrice * 1.25;
            totalCost = totalCost.add(BigDecimal.valueOf(
                    inputCost + outputCost + cacheReadCost + cacheWriteCost));
        }
        return totalCost.setScale(4, RoundingMode.HALF_UP);
    }

    public record UsageSummary(long tokensIn, long tokensOut,
                               long cacheReadTokens, long cacheWriteTokens) {
        /** Backwards-compatible constructor that zeros out cache counters. */
        public UsageSummary(long tokensIn, long tokensOut) {
            this(tokensIn, tokensOut, 0L, 0L);
        }

        /**
         * Cache hit ratio: fraction of input tokens served from cache.
         * Returns a value in [0.0, 1.0]. When {@code tokensIn == 0} returns 0.
         */
        public double cacheHitRatio() {
            if (tokensIn == 0) {
                return 0.0;
            }
            return (double) cacheReadTokens / (double) tokensIn;
        }
    }

    public record AgentUsage(String agent, String provider, String model,
                             long tokensIn, long tokensOut,
                             long cacheReadTokens, long cacheWriteTokens) {
        /** Backwards-compatible constructor that zeros out cache counters. */
        public AgentUsage(String agent, String provider, String model,
                          long tokensIn, long tokensOut) {
            this(agent, provider, model, tokensIn, tokensOut, 0L, 0L);
        }
    }
}
