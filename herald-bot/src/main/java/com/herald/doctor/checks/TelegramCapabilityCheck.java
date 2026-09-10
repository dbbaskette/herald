package com.herald.doctor.checks;

import com.herald.config.ProviderCapabilities;
import com.herald.doctor.HealthCheck;
import org.springframework.core.env.Environment;

public record TelegramCapabilityCheck(Environment environment) implements HealthCheck {
    public String name() { return "Telegram"; }
    public Result run() {
        var status = ProviderCapabilities.telegram(environment);
        // An absent optional surface is a valid no-Telegram configuration.
        return Result.ok(status.state() + ": " + status.message());
    }
}
