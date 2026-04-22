package com.herald.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryLogWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void formatLineRendersTimestampEventAndQuotedFields() {
        Instant when = Instant.parse("2026-04-22T14:02:03Z");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("path", "user_role.md");
        fields.put("note", "a note with spaces");

        String line = MemoryLogWriter.formatLine(when, "CREATE", fields);

        assertThat(line).isEqualTo(
                "2026-04-22T14:02:03Z CREATE path=user_role.md note=\"a note with spaces\"");
    }

    @Test
    void formatLineEscapesNewlinesAndQuotes() {
        Instant when = Instant.parse("2026-04-22T14:02:03Z");
        String line = MemoryLogWriter.formatLine(when, "STRREPLACE",
                Map.of("text", "line1\nline2 \"quoted\""));

        assertThat(line).contains("text=\"line1\\nline2 \\\"quoted\\\"\"");
        assertThat(line.lines().count()).isEqualTo(1);
    }

    @Test
    void appendEventCreatesFileAndAppendsLines() throws IOException {
        Path logFile = tempDir.resolve("log.md");

        MemoryLogWriter.appendEvent(logFile, "CREATE", Map.of("path", "a.md"));
        MemoryLogWriter.appendEvent(logFile, "DELETE", Map.of("path", "b.md"));

        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).endsWith("CREATE path=a.md");
        assertThat(lines.get(1)).endsWith("DELETE path=b.md");
    }

    @Test
    void writeHotOverwritesWithTimestampedContent() throws IOException {
        Path hotFile = tempDir.resolve("hot.md");
        MemoryLogWriter.writeHot(hotFile, "first");
        MemoryLogWriter.writeHot(hotFile, "second");

        String content = Files.readString(hotFile, StandardCharsets.UTF_8);
        assertThat(content)
                .startsWith("# Hot Context")
                .contains("Last updated:")
                .contains("second")
                .doesNotContain("first");
    }

    @Test
    void appendEventIsThreadSafe() throws Exception {
        Path logFile = tempDir.resolve("log.md");
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        MemoryLogWriter.appendEvent(logFile, "CREATE",
                                Map.of("path", "t" + tid + "-" + i + ".md"));
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(threads * perThread);
        for (String line : lines) {
            assertThat(line).matches("\\S+ CREATE path=t\\d+-\\d+\\.md");
        }
    }
}
