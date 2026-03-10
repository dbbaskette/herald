package com.herald.ui.config;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void dataSourceCreatesDbFileWithWalMode() throws Exception {
        String dbPath = tempDir.resolve("test.db").toString();
        DataSourceConfig config = new DataSourceConfig();
        HeraldUiConfig uiConfig = new HeraldUiConfig(dbPath, "/tmp/skills", "");

        DataSource dataSource = config.dataSource(uiConfig);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
        }
    }

    @Test
    void resolveDbPathExpandsTilde() {
        DataSourceConfig config = new DataSourceConfig();
        String resolved = config.resolveDbPath("~/test.db");
        assertThat(resolved).isEqualTo(System.getProperty("user.home") + "/test.db");
    }

    @Test
    void resolveDbPathLeavesAbsolutePathUnchanged() {
        DataSourceConfig config = new DataSourceConfig();
        String resolved = config.resolveDbPath("/tmp/test.db");
        assertThat(resolved).isEqualTo("/tmp/test.db");
    }

    @Test
    void dataSourceCreatesParentDirectory() {
        String dbPath = tempDir.resolve("subdir/nested/test.db").toString();
        DataSourceConfig config = new DataSourceConfig();
        HeraldUiConfig uiConfig = new HeraldUiConfig(dbPath, "/tmp/skills", "");

        config.dataSource(uiConfig);

        assertThat(Path.of(dbPath).getParent()).exists();
    }
}
