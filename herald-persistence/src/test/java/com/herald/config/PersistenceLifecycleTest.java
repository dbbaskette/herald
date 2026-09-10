package com.herald.config;

import com.herald.agent.AgentMetrics;
import com.herald.agent.BudgetPolicy;
import com.herald.agent.UsageTracker;
import com.herald.cron.*;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class PersistenceLifecycleTest {
    @TempDir Path dir;
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(HeraldConfig.class)
    static class Binding {}
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Binding.class, DataSourceConfig.class)
                .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class));
    }
    @Test void taskModeOverridesConfiguredAssistantPersistenceAndCronWithoutCreatingFiles() {
        Path database = dir.resolve("not-created/db.sqlite");
        runner().withUserConfiguration(CronRepository.class,CronService.class,CronTools.class,CronCommandPoller.class,
                        BriefingJob.class,ParallelBriefingService.class,UsageTracker.class,AgentMetrics.class,BudgetPolicy.class)
                .withPropertyValues("agents=fixture.md", "herald.memory.db-path="+database,
                        "herald.persistence.enabled=true", "herald.cron.enabled=true")
                .run(c -> {
                    assertThat(c).hasNotFailed().doesNotHaveBean(DataSource.class).doesNotHaveBean(JdbcTemplate.class)
                            .doesNotHaveBean(CronService.class).doesNotHaveBean(CronCommandPoller.class)
                            .doesNotHaveBean(UsageTracker.class).doesNotHaveBean(AgentMetrics.class);
                    assertThat(Files.exists(database.getParent())).isFalse();
                });
    }
    @Test void explicitlyDisabledOrBlankPathCreatesNoDatabase() {
        runner().withPropertyValues("herald.memory.db-path="+dir.resolve("disabled.sqlite"),"herald.persistence.enabled=false")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(DataSource.class));
        runner().withPropertyValues("herald.memory.db-path=   ")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(DataSource.class));
        assertThat(Files.exists(dir.resolve("disabled.sqlite"))).isFalse();
    }
    @Test void configuredAssistantPersistenceInitializesRealFixtureDatabaseWhileCronCanBeDisabled() {
        Path database = dir.resolve("enabled.sqlite");
        runner().withUserConfiguration(CronRepository.class,CronService.class,CronTools.class,CronCommandPoller.class,
                        BriefingJob.class,ParallelBriefingService.class)
                .withPropertyValues("herald.memory.db-path="+database,"herald.cron.enabled=false")
                .run(c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(DataSource.class).hasSingleBean(JdbcTemplate.class)
                            .doesNotHaveBean(CronService.class).doesNotHaveBean(CronCommandPoller.class);
                    assertThat(c.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM cron_jobs",Integer.class)).isNotNull();
                });
        assertThat(Files.exists(database)).isTrue();
    }
}
