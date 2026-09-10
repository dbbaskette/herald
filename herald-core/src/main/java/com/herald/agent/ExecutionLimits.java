package com.herald.agent;

import java.time.Duration;

/** Per-turn bounds; monetary amounts are estimates using operator-supplied rates. */
public record ExecutionLimits(int maxSteps, Duration deadline, long maxTokens,
                              double maxCostUsd, double inputUsdPerMillion, double outputUsdPerMillion) {
    public ExecutionLimits {
        if (maxSteps < 1 || deadline == null || deadline.isNegative() || deadline.isZero()
                || maxTokens < 1 || !Double.isFinite(maxCostUsd) || maxCostUsd < 0
                || !Double.isFinite(inputUsdPerMillion) || inputUsdPerMillion < 0
                || !Double.isFinite(outputUsdPerMillion) || outputUsdPerMillion < 0
                || (maxCostUsd > 0 && (inputUsdPerMillion == 0 || outputUsdPerMillion == 0))) {
            throw new IllegalArgumentException("Execution limits must be positive; a cost cap requires positive input/output rates");
        }
    }
    public static ExecutionLimits defaults() {
        return new ExecutionLimits(32, Duration.ofMinutes(5), 200_000, 0, 0, 0);
    }
}
