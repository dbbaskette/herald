package com.herald.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import static org.assertj.core.api.Assertions.assertThat;

class AssistantSchedulingLifecycleTest {
    @Test void taskModeDoesNotActivateSpringSchedulerEvenWithAssistantPropertiesPresent() {
        new ApplicationContextRunner().withUserConfiguration(AssistantSchedulingConfiguration.class)
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("agents=fixture.md", "herald.memory.db-path=/unused-fixture.sqlite",
                        "herald.telegram.bot-token=fixture", "herald.telegram.allowed-chat-id=123")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(TaskScheduler.class)
                        .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
    @Test void assistantWithEveryScheduledCapabilityDisabledHasNoScheduler() {
        new ApplicationContextRunner().withUserConfiguration(AssistantSchedulingConfiguration.class)
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("herald.cron.enabled=false", "herald.meetingnotes.enabled=false",
                        "herald.telegram.bot-token=", "herald.telegram.allowed-chat-id=")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(TaskScheduler.class)
                        .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
    @Test void botDoesNotFallBackToAutomaticDatabaseCreationWhenExplicitPersistenceIsDisabled() {
        assertThat(com.herald.HeraldApplication.class.getAnnotation(SpringBootApplication.class).exclude())
                .contains(org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class);
    }
}
