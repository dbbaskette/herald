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

class MessageRepositoryTest {

    @TempDir
    Path tempDir;

    private MessageRepository repository;
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
        repository = new MessageRepository(jdbcTemplate);
    }

    @Test
    void listRecentReturnsEmptyWhenNoData() {
        List<Map<String, Object>> results = repository.listRecent(10);
        assertThat(results).isEmpty();
    }

    @Test
    void listRecentReturnsRowsInDescOrder() {
        jdbcTemplate.update("INSERT INTO messages (role, content) VALUES (?, ?)", "user", "hello");
        jdbcTemplate.update("INSERT INTO messages (role, content) VALUES (?, ?)", "assistant", "hi there");

        List<Map<String, Object>> results = repository.listRecent(10);
        assertThat(results).hasSize(2);
        // Most recent first
        assertThat(results.get(0).get("role")).isEqualTo("assistant");
        assertThat(results.get(1).get("role")).isEqualTo("user");
    }

    @Test
    void listRecentRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update("INSERT INTO messages (role, content) VALUES (?, ?)", "user", "msg" + i);
        }

        List<Map<String, Object>> results = repository.listRecent(3);
        assertThat(results).hasSize(3);
    }

    @Test
    void getByIdReturnsMatchingRow() {
        jdbcTemplate.update("INSERT INTO messages (role, content) VALUES (?, ?)", "user", "test message");

        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM messages WHERE content = ?", Long.class, "test message");

        Map<String, Object> result = repository.getById(id);
        assertThat(result).isNotNull();
        assertThat(result.get("role")).isEqualTo("user");
        assertThat(result.get("content")).isEqualTo("test message");
    }

    @Test
    void getByIdReturnsNullForMissing() {
        assertThat(repository.getById(99999)).isNull();
    }
}
