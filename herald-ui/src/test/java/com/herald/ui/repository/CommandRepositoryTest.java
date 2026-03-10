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

class CommandRepositoryTest {

    @TempDir
    Path tempDir;

    private CommandRepository repository;
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
        repository = new CommandRepository(jdbcTemplate);
    }

    @Test
    void listRecentReturnsEmptyWhenNoData() {
        List<Map<String, Object>> results = repository.listRecent(10);
        assertThat(results).isEmpty();
    }

    @Test
    void insertCreatesCommand() {
        Map<String, Object> result = repository.insert("RELOAD_CRON", "{\"jobName\":\"test\"}");

        assertThat(result).isNotNull();
        assertThat(result.get("type")).isEqualTo("RELOAD_CRON");
        assertThat(result.get("payload")).isEqualTo("{\"jobName\":\"test\"}");
        assertThat(result.get("status")).isEqualTo("pending");
        assertThat(result.get("id")).isNotNull();
        assertThat(result.get("created_at")).isNotNull();
    }

    @Test
    void listRecentReturnsInsertedCommands() {
        repository.insert("CMD_A", "payload-a");
        repository.insert("CMD_B", "payload-b");

        List<Map<String, Object>> results = repository.listRecent(10);
        assertThat(results).hasSize(2);
        // Most recent first
        assertThat(results.get(0).get("type")).isEqualTo("CMD_B");
        assertThat(results.get(1).get("type")).isEqualTo("CMD_A");
    }

    @Test
    void listRecentRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            repository.insert("CMD", "payload-" + i);
        }

        List<Map<String, Object>> results = repository.listRecent(3);
        assertThat(results).hasSize(3);
    }
}
