package com.herald.cron;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

class CronRepositoryTest {

    @TempDir
    Path tempDir;

    private CronRepository repository;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("test.db").toString();
        DataSource dataSource = DataSourceBuilder.create()
                .driverClassName("org.sqlite.JDBC")
                .url("jdbc:sqlite:" + dbPath)
                .build();

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        populator.setContinueOnError(true);

        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(populator);
        initializer.afterPropertiesSet();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new CronRepository(jdbcTemplate);
    }

    @Test
    void saveAndFindByName() {
        CronJob job = new CronJob(null, "daily-report", "0 0 9 * * *", "Give me a daily summary", null, true);
        repository.save(job);

        CronJob found = repository.findByName("daily-report");
        assertThat(found).isNotNull();
        assertThat(found.name()).isEqualTo("daily-report");
        assertThat(found.schedule()).isEqualTo("0 0 9 * * *");
        assertThat(found.prompt()).isEqualTo("Give me a daily summary");
        assertThat(found.enabled()).isTrue();
        assertThat(found.id()).isNotNull();
    }

    @Test
    void findByNameReturnsNullForMissing() {
        assertThat(repository.findByName("nonexistent")).isNull();
    }

    @Test
    void findAllReturnsAllJobs() {
        repository.save(new CronJob(null, "job-a", "0 0 9 * * *", "prompt a", null, true));
        repository.save(new CronJob(null, "job-b", "0 0 10 * * *", "prompt b", null, false));

        List<CronJob> jobs = repository.findAll();
        assertThat(jobs).hasSize(2);
        assertThat(jobs).extracting(CronJob::name).containsExactly("job-a", "job-b");
    }

    @Test
    void saveUpdatesExistingJob() {
        repository.save(new CronJob(null, "job", "0 0 9 * * *", "old prompt", null, true));
        repository.save(new CronJob(null, "job", "0 0 10 * * *", "new prompt", null, false));

        CronJob found = repository.findByName("job");
        assertThat(found.schedule()).isEqualTo("0 0 10 * * *");
        assertThat(found.prompt()).isEqualTo("new prompt");
        assertThat(found.enabled()).isFalse();
    }

    @Test
    void updateLastRun() {
        repository.save(new CronJob(null, "job", "0 0 9 * * *", "prompt", null, true));
        LocalDateTime now = LocalDateTime.of(2026, 3, 10, 12, 0, 0);
        repository.updateLastRun("job", now);

        CronJob found = repository.findByName("job");
        assertThat(found.lastRun()).isEqualTo(now);
    }

    @Test
    void setEnabled() {
        repository.save(new CronJob(null, "job", "0 0 9 * * *", "prompt", null, true));

        repository.setEnabled("job", false);
        assertThat(repository.findByName("job").enabled()).isFalse();

        repository.setEnabled("job", true);
        assertThat(repository.findByName("job").enabled()).isTrue();
    }

    @Test
    void deleteRemovesJob() {
        repository.save(new CronJob(null, "job", "0 0 9 * * *", "prompt", null, true));
        assertThat(repository.delete("job")).isTrue();
        assertThat(repository.findByName("job")).isNull();
    }

    @Test
    void deleteReturnsFalseForMissing() {
        assertThat(repository.delete("nonexistent")).isFalse();
    }
}
