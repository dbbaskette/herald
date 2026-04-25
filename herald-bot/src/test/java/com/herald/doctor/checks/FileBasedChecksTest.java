package com.herald.doctor.checks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import com.herald.doctor.HealthCheck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FileBasedChecksTest {

    @Test
    void javaRuntimeCheckPassesOnThisJvm() {
        HealthCheck.Result r = new JavaRuntimeCheck().run();
        // Tests run on Herald's supported JDK, so this is always OK here.
        assertThat(r.status()).isEqualTo(HealthCheck.Status.OK);
        assertThat(r.message()).contains(System.getProperty("java.version"));
    }

    @Test
    void databaseCheckWarnsWhenMissing(@TempDir Path tempDir) {
        DatabaseCheck check = new DatabaseCheck(tempDir.resolve("nonexistent.db"));
        HealthCheck.Result r = check.run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.WARN);
        assertThat(r.message()).contains("missing");
    }

    @Test
    void databaseCheckPassesWithWalDatabase(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("CREATE TABLE dummy (x INTEGER)");
        }

        HealthCheck.Result r = new DatabaseCheck(dbFile).run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.OK);
        assertThat(r.message()).contains("journal=wal");
    }

    @Test
    void databaseCheckWarnsOnNonWalJournal(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("rollback.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=DELETE");
            stmt.execute("CREATE TABLE dummy (x INTEGER)");
        }

        HealthCheck.Result r = new DatabaseCheck(dbFile).run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.WARN);
        assertThat(r.message()).contains("journal=");
    }

    @Test
    void memoryDirCheckWarnsWhenMissing(@TempDir Path tempDir) {
        HealthCheck.Result r = new MemoryDirCheck(tempDir.resolve("never")).run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.WARN);
    }

    @Test
    void memoryDirCheckPassesWithValidLayout(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("MEMORY.md"),
                "# Memory\n\n## User\n\n## Feedback\n\n## Projects\n\n## References\n");
        Files.writeString(tempDir.resolve("log.md"), "some log");
        Files.writeString(tempDir.resolve("hot.md"), "");

        HealthCheck.Result r = new MemoryDirCheck(tempDir).run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.OK);
        assertThat(r.message()).contains("MEMORY.md ok");
    }

    @Test
    void memoryDirCheckWarnsOnMissingSections(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("MEMORY.md"), "# Memory\n\n## User\n");
        Files.writeString(tempDir.resolve("hot.md"), "");

        HealthCheck.Result r = new MemoryDirCheck(tempDir).run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.WARN);
        assertThat(r.message()).contains("missing sections");
    }

    @Test
    void skillsDirCheckWarnsWhenMissing(@TempDir Path tempDir) {
        HealthCheck.Result r = new SkillsDirCheck(tempDir.resolve("never")).run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.WARN);
    }

    @Test
    void skillsDirCheckCountsValidSkills(@TempDir Path tempDir) throws IOException {
        Path goodSkill = tempDir.resolve("good");
        Files.createDirectory(goodSkill);
        Files.writeString(goodSkill.resolve("SKILL.md"),
                "---\nname: good\ndescription: does a thing\n---\nBody.\n");

        Path badSkill = tempDir.resolve("bad");
        Files.createDirectory(badSkill);
        Files.writeString(badSkill.resolve("SKILL.md"), "No frontmatter here");

        HealthCheck.Result r = new SkillsDirCheck(tempDir).run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.WARN);
        assertThat(r.message()).contains("1 skill(s)");
        assertThat(r.message()).contains("bad");
    }

    @Test
    void skillsDirCheckAllValidPasses(@TempDir Path tempDir) throws IOException {
        Path s = tempDir.resolve("one");
        Files.createDirectory(s);
        Files.writeString(s.resolve("SKILL.md"),
                "---\nname: one\ndescription: just one\n---\nBody.\n");

        HealthCheck.Result r = new SkillsDirCheck(tempDir).run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.OK);
    }

    @Test
    void portCheckReportsFreePort() {
        // Port 0 is special — bind asks the OS for any available port, always succeeds.
        HealthCheck.Result r = new PortCheck("test", 0).run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.OK);
    }

    @Test
    void humanBytesFormatting() {
        assertThat(DatabaseCheck.humanBytes(500)).isEqualTo("500 B");
        assertThat(DatabaseCheck.humanBytes(2048)).isEqualTo("2.0 KB");
        assertThat(DatabaseCheck.humanBytes(5L * 1024 * 1024)).isEqualTo("5.0 MB");
    }

    @Test
    void externalCliCheckReturnsWarnWhenCommandMissing() {
        HealthCheck.Result r = new ExternalCliCheck("Nonexistent",
                "definitely-not-a-real-cli-name-xyz", "install it").run();
        assertThat(r.status()).isEqualTo(HealthCheck.Status.WARN);
        assertThat(r.fixHint()).isEqualTo("install it");
    }
}
