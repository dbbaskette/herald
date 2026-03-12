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
        ensureSchema(dataSource);
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

    /**
     * Create core tables if they don't exist yet. On a fresh install the bot
     * (which normally creates the schema) may not have run, so the UI must be
     * able to start independently.
     */
    private void ensureSchema(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    role        TEXT NOT NULL,
                    content     TEXT,
                    tool_calls  TEXT,
                    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memory (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    key        TEXT UNIQUE NOT NULL,
                    value      TEXT NOT NULL,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS cron_jobs (
                    id        INTEGER PRIMARY KEY AUTOINCREMENT,
                    name      TEXT UNIQUE NOT NULL,
                    schedule  TEXT NOT NULL,
                    prompt    TEXT NOT NULL,
                    last_run  DATETIME,
                    enabled   INTEGER DEFAULT 1,
                    built_in  INTEGER DEFAULT 0
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS commands (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    type         TEXT NOT NULL,
                    payload      TEXT,
                    status       TEXT DEFAULT 'pending',
                    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
                    completed_at DATETIME
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key        TEXT PRIMARY KEY,
                    value      TEXT NOT NULL,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )""");
            log.info("Database schema verified");
        } catch (SQLException e) {
            log.warn("Failed to ensure schema: {}", e.getMessage());
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
