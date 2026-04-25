package com.herald.doctor.checks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.herald.doctor.HealthCheck;

/**
 * Confirms the SQLite file exists, is readable, passes PRAGMA integrity_check,
 * and is in WAL journal mode. A corrupt DB is the #1 non-obvious failure
 * mode — a slow disk, a killed process mid-write, or a kernel panic can all
 * wedge it, and the symptoms (tool errors, memory loss) look like bugs elsewhere.
 */
public class DatabaseCheck implements HealthCheck {

    private final Path dbPath;

    public DatabaseCheck(Path dbPath) {
        this.dbPath = dbPath;
    }

    public DatabaseCheck() {
        this(resolveDefaultPath());
    }

    private static Path resolveDefaultPath() {
        String raw = System.getenv().getOrDefault("HERALD_DB_PATH", "~/.herald/herald.db");
        if (raw.startsWith("~/")) {
            raw = System.getProperty("user.home") + raw.substring(1);
        }
        return Path.of(raw);
    }

    @Override
    public String name() {
        return "SQLite database";
    }

    @Override
    public Result run() {
        if (!Files.exists(dbPath)) {
            return Result.warn("missing at " + dbPath,
                    "File will be created on first launch; warning only if you expected one");
        }
        if (!Files.isReadable(dbPath)) {
            return Result.fail("unreadable at " + dbPath,
                    "chmod 644 " + dbPath);
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {
            String journalMode = queryString(stmt, "PRAGMA journal_mode;");
            String integrity = queryString(stmt, "PRAGMA integrity_check;");
            long size = Files.size(dbPath);
            String sizeStr = humanBytes(size);
            if (!"ok".equalsIgnoreCase(integrity)) {
                return Result.fail("integrity_check returned: " + integrity,
                        "Backup the file, then delete — Herald will rebuild on next launch");
            }
            boolean walOn = "wal".equalsIgnoreCase(journalMode);
            String msg = String.format("%s, %s, journal=%s", dbPath.getFileName(), sizeStr, journalMode);
            return walOn ? Result.ok(msg) : Result.warn(msg, "Expected WAL mode; concurrent reads may block");
        } catch (SQLException | java.io.IOException e) {
            return Result.fail("failed to open: " + e.getMessage(), null);
        }
    }

    private static String queryString(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "?";
        }
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024);
        return String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
