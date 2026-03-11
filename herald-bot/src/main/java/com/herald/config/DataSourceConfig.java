package com.herald.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DataSource dataSource(HeraldConfig heraldConfig) {
        String dbPath = resolveDbPath(heraldConfig.dbPath());
        ensureParentDirectory(dbPath);

        DataSource dataSource = DataSourceBuilder.create()
                .driverClassName("org.sqlite.JDBC")
                .url("jdbc:sqlite:" + dbPath)
                .build();

        enableWalMode(dataSource);
        return dataSource;
    }

    @Bean
    public DataSourceInitializer dataSourceInitializer(DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        // continueOnError=true so IF NOT EXISTS clauses don't fail on re-run;
        // genuine SQL errors are logged as warnings below by Spring's ResourceDatabasePopulator
        populator.setContinueOnError(true);

        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(populator);
        log.info("DataSourceInitializer configured with schema.sql (continueOnError=true)");
        return initializer;
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public ChatMemoryRepository chatMemoryRepository(JdbcTemplate jdbcTemplate) {
        return new JsonChatMemoryRepository(jdbcTemplate);
    }

    String resolveDbPath(String path) {
        if (path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private void ensureParentDirectory(String dbPath) {
        Path parent = Paths.get(dbPath).getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
                log.info("Created Herald data directory: {}", parent);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create directory: " + parent, e);
            }
        }
    }

    private void enableWalMode(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            log.info("SQLite WAL mode enabled");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to enable WAL mode", e);
        }
    }
}
