package com.herald.ui.repository;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

class CronJobRepositoryTest {

    @TempDir
    Path tempDir;

    private CronJobRepository repository;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("test.db").toString();
        DataSource dataSource = DataSourceBuilder.create()
                .driverClassName("org.sqlite.JDBC")
                .url("jdbc:sqlite:" + dbPath)
                .build();

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("test-schema.sql"));
        populator.setContinueOnError(true);

        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(populator);
        initializer.afterPropertiesSet();

        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new CronJobRepository(jdbcTemplate);
    }

    @Test
    void listAllReturnsEmptyWhenNoData() {
        List<Map<String, Object>> results = repository.listAll();
        assertThat(results).isEmpty();
    }

    @Test
    void listAllReturnsInsertedRows() {
        jdbcTemplate.update(
                "INSERT INTO cron_jobs (name, schedule, prompt, enabled, built_in) VALUES (?, ?, ?, 1, 0)",
                "job-a", "0 9 * * *", "do something");
        jdbcTemplate.update(
                "INSERT INTO cron_jobs (name, schedule, prompt, enabled, built_in) VALUES (?, ?, ?, 0, 0)",
                "job-b", "0 10 * * *", "do another thing");

        List<Map<String, Object>> results = repository.listAll();
        assertThat(results).hasSize(2);
        assertThat(results).extracting(r -> r.get("name")).containsExactly("job-a", "job-b");
    }

    @Test
    void getByIdReturnsMatchingRow() {
        jdbcTemplate.update(
                "INSERT INTO cron_jobs (name, schedule, prompt) VALUES (?, ?, ?)",
                "test-job", "0 9 * * *", "prompt");

        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM cron_jobs WHERE name = ?", Long.class, "test-job");

        Map<String, Object> result = repository.getById(id);
        assertThat(result).isNotNull();
        assertThat(result.get("name")).isEqualTo("test-job");
        assertThat(result.get("schedule")).isEqualTo("0 9 * * *");
    }

    @Test
    void getByIdReturnsNullForMissing() {
        assertThat(repository.getById(99999)).isNull();
    }
}
