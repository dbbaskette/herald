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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that verifies the full DataSource → JdbcTemplate → JdbcChatMemoryRepository
 * wiring chain works end-to-end, including schema initialization.
 */
class DataSourceIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void fullBeanWiringChainFunctional() throws SQLException {
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory(tempDir.resolve("herald-integration.db").toString()), null, null, null, null, null, null, null, null, null);

        // Wire beans in the same order Spring would
        DataSourceConfig dsConfig = new DataSourceConfig();
        DataSource dataSource = dsConfig.dataSource(config);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        // Run schema initialization (what DataSourceInitializer bean does at startup)
        DataSourceInitializer initializer = dsConfig.dataSourceInitializer(dataSource);
        initializer.afterPropertiesSet();

        // Create JdbcChatMemoryRepository (validates it can be built with the wired beans)
        ChatMemoryRepository chatMemoryRepository = dsConfig.chatMemoryRepository(jdbcTemplate);

        assertThat(dataSource).isNotNull();
        assertThat(jdbcTemplate).isNotNull();
        assertThat(chatMemoryRepository).isNotNull();

        // Verify schema tables are present after initialization
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            assertThat(tables).containsExactlyInAnyOrder(
                    "messages", "memory", "cron_jobs", "commands", "model_usage",
                    "model_overrides", "settings", "SPRING_AI_CHAT_MEMORY");
        }
    }
}
