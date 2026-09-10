package com.herald.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class ProviderConfiguredCondition implements Condition {
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var attributes = metadata.getAnnotationAttributes(ConditionalOnProvider.class.getName());
        return attributes != null && ProviderCapabilities.configured(context.getEnvironment(), (String) attributes.get("value"));
    }
}
