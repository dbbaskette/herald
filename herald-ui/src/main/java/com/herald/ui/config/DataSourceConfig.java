package com.herald.ui.config;

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
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    DataSource dataSource(HeraldUiConfig heraldUiConfig) {
        String dbPath = resolveDbPath(heraldUiConfig.dbPath());
        ensureParentDirectory(dbPath);

        DataSource dataSource = DataSourceBuilder.create()
                .driverClassName("org.sqlite.JDBC")
                .url("jdbc:sqlite:" + dbPath)
                .build();

        enableWalMode(dataSource);
        return dataSource;
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
