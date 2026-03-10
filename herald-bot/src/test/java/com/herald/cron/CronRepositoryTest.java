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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        CronJob job = new CronJob(null, "daily-report", "0 0 9 * * *", "Give me a daily summary", null, true, false);
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
        repository.save(new CronJob(null, "job-a", "0 0 9 * * *", "prompt a", null, true, false));
        repository.save(new CronJob(null, "job-b", "0 0 10 * * *", "prompt b", null, false, false));

        List<CronJob> jobs = repository.findAll();
        // 2 seed built-in jobs + 2 custom jobs
        assertThat(jobs).hasSize(4);
        assertThat(jobs).extracting(CronJob::name).contains("job-a", "job-b");
    }

    @Test
    void saveUpdatesExistingJob() {
        repository.save(new CronJob(null, "job", "0 0 9 * * *", "old prompt", null, true, false));
        repository.save(new CronJob(null, "job", "0 0 10 * * *", "new prompt", null, false, false));

        CronJob found = repository.findByName("job");
        assertThat(found.schedule()).isEqualTo("0 0 10 * * *");
        assertThat(found.prompt()).isEqualTo("new prompt");
        assertThat(found.enabled()).isFalse();
    }

    @Test
    void updateLastRun() {
        repository.save(new CronJob(null, "job", "0 0 9 * * *", "prompt", null, true, false));
        LocalDateTime now = LocalDateTime.of(2026, 3, 10, 12, 0, 0);
        repository.updateLastRun("job", now);

        CronJob found = repository.findByName("job");
        assertThat(found.lastRun()).isEqualTo(now);
    }

    @Test
    void setEnabled() {
        repository.save(new CronJob(null, "job", "0 0 9 * * *", "prompt", null, true, false));

        repository.setEnabled("job", false);
        assertThat(repository.findByName("job").enabled()).isFalse();

        repository.setEnabled("job", true);
        assertThat(repository.findByName("job").enabled()).isTrue();
    }

    @Test
    void deleteRemovesJob() {
        repository.save(new CronJob(null, "job", "0 0 9 * * *", "prompt", null, true, false));
        assertThat(repository.delete("job")).isTrue();
        assertThat(repository.findByName("job")).isNull();
    }

    @Test
    void deleteReturnsFalseForMissing() {
        assertThat(repository.delete("nonexistent")).isFalse();
    }

    @Test
    void seedDataContainsMorningBriefing() {
        CronJob job = repository.findByName("morning-briefing");
        assertThat(job).isNotNull();
        assertThat(job.schedule()).isEqualTo("0 7 * * 1-5");
        assertThat(job.builtIn()).isTrue();
        assertThat(job.enabled()).isTrue();
        assertThat(job.prompt()).contains("weather");
    }

    @Test
    void seedDataContainsWeeklyReview() {
        CronJob job = repository.findByName("weekly-review");
        assertThat(job).isNotNull();
        assertThat(job.schedule()).isEqualTo("0 18 * * 5");
        assertThat(job.builtIn()).isTrue();
        assertThat(job.enabled()).isTrue();
        assertThat(job.prompt()).contains("recap");
    }

    @Test
    void deleteThrowsForBuiltInJob() {
        CronJob job = repository.findByName("morning-briefing");
        assertThat(job).isNotNull();
        assertThatThrownBy(() -> repository.delete("morning-briefing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Built-in jobs cannot be deleted");
        assertThat(repository.findByName("morning-briefing")).isNotNull();
    }

    @Test
    void findEnabled() {
        repository.save(new CronJob(null, "enabled-job", "0 0 9 * * *", "prompt", null, true, false));
        repository.save(new CronJob(null, "disabled-job", "0 0 10 * * *", "prompt", null, false, false));

        List<CronJob> enabled = repository.findEnabled();
        assertThat(enabled).allMatch(CronJob::enabled);
        assertThat(enabled).extracting(CronJob::name).contains("morning-briefing", "weekly-review", "enabled-job");
        assertThat(enabled).extracting(CronJob::name).doesNotContain("disabled-job");
    }

    @Test
    void findById() {
        repository.save(new CronJob(null, "by-id-job", "0 0 9 * * *", "prompt", null, true, false));
        CronJob byName = repository.findByName("by-id-job");

        CronJob byId = repository.findById(byName.id());
        assertThat(byId).isNotNull();
        assertThat(byId.name()).isEqualTo("by-id-job");
    }

    @Test
    void findByIdReturnsNullForMissing() {
        assertThat(repository.findById(99999)).isNull();
    }

    @Test
    void findAllIncludesBuiltInFlag() {
        List<CronJob> jobs = repository.findAll();
        assertThat(jobs).anyMatch(j -> j.name().equals("morning-briefing") && j.builtIn());
        assertThat(jobs).anyMatch(j -> j.name().equals("weekly-review") && j.builtIn());
    }
}
