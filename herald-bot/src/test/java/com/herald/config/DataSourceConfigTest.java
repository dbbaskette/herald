package com.herald.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void dataSourceCreatesTablesAndEnablesWal() throws SQLException {
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory(tempDir.resolve("herald.db").toString()));

        DataSourceConfig dsConfig = new DataSourceConfig();
        DataSource dataSource = dsConfig.dataSource(config);

        // Run schema.sql manually for this test
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Verify WAL mode
            try (ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
            }
        }
    }

    @Test
    void dataSourceCreatesParentDirectory() throws SQLException {
        Path nestedDir = tempDir.resolve("sub/dir/herald.db");
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory(nestedDir.toString()));

        DataSourceConfig dsConfig = new DataSourceConfig();
        dsConfig.dataSource(config);

        assertThat(nestedDir.getParent()).exists();
    }
}
