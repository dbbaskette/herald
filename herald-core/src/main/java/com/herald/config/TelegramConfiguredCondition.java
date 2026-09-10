package com.herald.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Avoid constructing Telegram clients, pollers or callbacks for incomplete configuration. */
public final class TelegramConfiguredCondition implements Condition {
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return ProviderCapabilities.telegram(context.getEnvironment()).state() == CapabilityState.HEALTHY;
    }
}
