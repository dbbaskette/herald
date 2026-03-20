package com.herald.memory;

import java.nio.file.Path;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRepositoryTest {

    @TempDir
    Path tempDir;

    private MemoryRepository repository;

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
        repository = new MemoryRepository(jdbcTemplate);
    }

    @Test
    void setAndGet() {
        repository.set("project", "Herald bot");
        assertThat(repository.get("project")).isEqualTo("Herald bot");
    }

    @Test
    void setUpdatesExistingKey() {
        repository.set("project", "Herald bot");
        repository.set("project", "Herald bot v2");
        assertThat(repository.get("project")).isEqualTo("Herald bot v2");
    }

    @Test
    void getReturnsNullForMissingKey() {
        assertThat(repository.get("nonexistent")).isNull();
    }

    @Test
    void listAllReturnsAllEntries() {
        repository.set("name", "Dan");
        repository.set("project", "Herald");

        Map<String, String> entries = repository.listAll();
        assertThat(entries).containsEntry("name", "Dan")
                .containsEntry("project", "Herald")
                .hasSize(2);
    }

    @Test
    void listAllReturnsEmptyMapWhenNoEntries() {
        assertThat(repository.listAll()).isEmpty();
    }

    @Test
    void deleteRemovesEntry() {
        repository.set("project", "Herald");
        assertThat(repository.delete("project")).isTrue();
        assertThat(repository.get("project")).isNull();
    }

    @Test
    void deleteReturnsFalseForMissingKey() {
        assertThat(repository.delete("nonexistent")).isFalse();
    }
}
