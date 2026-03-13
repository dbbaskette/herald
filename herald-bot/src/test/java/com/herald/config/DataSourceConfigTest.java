package com.herald.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void dataSourceEnablesWalMode() throws SQLException {
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory(tempDir.resolve("herald.db").toString()), null, null, null, null, null);

        DataSourceConfig dsConfig = new DataSourceConfig();
        DataSource dataSource = dsConfig.dataSource(config);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
        }
    }

    @Test
    void dataSourceCreatesAllFiveTables() throws SQLException {
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory(tempDir.resolve("herald.db").toString()), null, null, null, null, null);

        DataSourceConfig dsConfig = new DataSourceConfig();
        DataSource dataSource = dsConfig.dataSource(config);

        // Apply schema.sql
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        populator.execute(dataSource);

        // Verify all expected tables exist
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            assertThat(tables).containsExactlyInAnyOrder(
                    "messages", "memory", "cron_jobs", "commands", "model_usage", "model_overrides",
                    "SPRING_AI_CHAT_MEMORY", "settings");
        }
    }

    @Test
    void dataSourceCreatesParentDirectory() throws SQLException {
        Path nestedDir = tempDir.resolve("sub/dir/herald.db");
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory(nestedDir.toString()), null, null, null, null, null);

        DataSourceConfig dsConfig = new DataSourceConfig();
        dsConfig.dataSource(config);

        assertThat(nestedDir.getParent()).exists();
    }

    @Test
    void resolveDbPathExpandsTilde() {
        DataSourceConfig dsConfig = new DataSourceConfig();
        String resolved = dsConfig.resolveDbPath("~/.herald/herald.db");
        String home = System.getProperty("user.home");
        assertThat(resolved).isEqualTo(home + "/.herald/herald.db");
        assertThat(resolved).doesNotStartWith("~");
    }

    @Test
    void resolveDbPathLeavesAbsolutePathUnchanged() {
        DataSourceConfig dsConfig = new DataSourceConfig();
        String resolved = dsConfig.resolveDbPath("/custom/path/herald.db");
        assertThat(resolved).isEqualTo("/custom/path/herald.db");
    }
}
