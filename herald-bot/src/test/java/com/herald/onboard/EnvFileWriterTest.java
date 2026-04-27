package com.herald.onboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class EnvFileWriterTest {

    @Test
    void writesNewFileWithHeader(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("FOO", "bar");
        updates.put("BAZ", "qux");

        Map<String, String> actions = EnvFileWriter.merge(env, updates);

        assertThat(actions).containsExactly(Map.entry("FOO", "added"), Map.entry("BAZ", "added"));
        assertThat(Files.readString(env)).contains("# Added by `herald onboard`")
                .contains("FOO=bar")
                .contains("BAZ=qux");
    }

    @Test
    void updatesExistingKeyInPlace(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, """
                # User comment
                FOO=old-value
                BAZ=keep-me
                """);

        Map<String, String> actions = EnvFileWriter.merge(env, Map.of("FOO", "new-value"));

        assertThat(actions).containsEntry("FOO", "updated");
        String written = Files.readString(env);
        assertThat(written).contains("FOO=new-value");
        assertThat(written).contains("BAZ=keep-me");
        assertThat(written).contains("# User comment");
    }

    @Test
    void preservesIdenticalValuesAsUnchanged(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "FOO=bar\n");

        Map<String, String> actions = EnvFileWriter.merge(env, Map.of("FOO", "bar"));

        assertThat(actions).containsEntry("FOO", "unchanged");
    }

    @Test
    void preservesQuotingOnInputAndStripsOnRead(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "FOO=\"quoted value\"\n");

        Map<String, String> actions = EnvFileWriter.merge(env, Map.of("FOO", "quoted value"));

        // Same value when stripped — should be unchanged.
        assertThat(actions).containsEntry("FOO", "unchanged");
    }

    @Test
    void quotesValuesWithWhitespace(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");

        EnvFileWriter.merge(env, Map.of("FOO", "value with spaces"));

        assertThat(Files.readString(env)).contains("FOO=\"value with spaces\"");
    }

    @Test
    void doesNotQuotePlainValues(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");

        EnvFileWriter.merge(env, Map.of("FOO", "plain-value-123"));

        assertThat(Files.readString(env)).contains("FOO=plain-value-123");
        assertThat(Files.readString(env)).doesNotContain("FOO=\"plain-value-123\"");
    }

    @Test
    void appendsNewKeysAfterExistingContent(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "EXISTING=value\n");

        EnvFileWriter.merge(env, Map.of("NEW", "added"));

        String written = Files.readString(env);
        assertThat(written).contains("EXISTING=value");
        assertThat(written).contains("NEW=added");
        assertThat(written).contains("# Added by `herald onboard`");
        // Order: existing first, header, then new key.
        assertThat(written.indexOf("EXISTING")).isLessThan(written.indexOf("# Added"));
        assertThat(written.indexOf("# Added")).isLessThan(written.indexOf("NEW"));
    }

    @Test
    void escapesEmbeddedQuotes(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");

        EnvFileWriter.merge(env, Map.of("FOO", "a \"quoted\" word"));

        // \" inside double quotes
        assertThat(Files.readString(env)).contains("FOO=\"a \\\"quoted\\\" word\"");
    }

    @Test
    void escapeAndStripQuotesRoundTrip() {
        assertThat(EnvFileWriter.escape("plain")).isEqualTo("plain");
        assertThat(EnvFileWriter.escape("with space")).isEqualTo("\"with space\"");
        assertThat(EnvFileWriter.escape("hash#in")).isEqualTo("\"hash#in\"");
        assertThat(EnvFileWriter.stripQuotes("\"quoted\"")).isEqualTo("quoted");
        assertThat(EnvFileWriter.stripQuotes("'single'")).isEqualTo("single");
        assertThat(EnvFileWriter.stripQuotes("plain")).isEqualTo("plain");
    }

    @Test
    void createsParentDirectoriesIfMissing(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve("deep/nested/.env");

        EnvFileWriter.merge(env, Map.of("FOO", "bar"));

        assertThat(Files.exists(env)).isTrue();
    }
}
