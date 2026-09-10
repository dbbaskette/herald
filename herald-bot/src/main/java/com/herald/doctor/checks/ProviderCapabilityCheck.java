package com.herald.doctor.checks;

import com.herald.config.ProviderCapabilities;
import com.herald.doctor.HealthCheck;
import org.springframework.core.env.Environment;

public record ProviderCapabilityCheck(Environment environment) implements HealthCheck {
    public String name() { return "Model provider"; }
    public Result run() {
        var resolution = ProviderCapabilities.resolve(environment);
        if (!resolution.usable()) return Result.fail("No configured provider is available for " + resolution.requestedProvider() + ".", "Configure a provider; see docs/provider-capabilities.md");
        return Result.ok("Configured provider: " + resolution.effectiveProvider()
                + (resolution.fallback() ? " (fallback from " + resolution.requestedProvider() + ")" : "") + "; remote health not probed.");
    }
}
