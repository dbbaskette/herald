package com.herald.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Do not activate Spring's scheduler infrastructure in an ephemeral task runtime. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Conditional(AssistantSchedulingConfiguration.Enabled.class)
public class AssistantSchedulingConfiguration {
    public static final class Enabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            return !IntegrationLifecycle.taskMode(env) && (IntegrationLifecycle.cronEnabled(env)
                    || IntegrationLifecycle.meetingNotesEnabled(env)
                    || ProviderCapabilities.telegram(env).state() == CapabilityState.HEALTHY);
        }
    }
}
