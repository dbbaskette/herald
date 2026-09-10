package com.herald.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class MeetingNotesEnabledCondition implements Condition {
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return IntegrationLifecycle.meetingNotesEnabled(context.getEnvironment());
    }
}
