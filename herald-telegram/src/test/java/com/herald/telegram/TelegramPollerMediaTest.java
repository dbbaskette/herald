package com.herald.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the static helpers extracted from {@link TelegramPoller}
 * for media handling (#320). Keeps the full Poller wiring out of scope —
 * these just verify inline text extraction and tool-availability fallbacks
 * work as advertised.
 */
class TelegramPollerMediaTest {

    @TempDir
    Path tempDir;

    @Test
    void extractTextFromPlainTextDocument() throws Exception {
        Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "hello world\nsecond line", StandardCharsets.UTF_8);

        String result = TelegramPoller.tryExtractDocumentText(file, "text/plain");

        assertThat(result).isEqualTo("hello world\nsecond line");
    }

    @Test
    void extractTextFromJsonDocument() throws Exception {
        Path file = tempDir.resolve("data.json");
        Files.writeString(file, "{\"k\":1}", StandardCharsets.UTF_8);

        assertThat(TelegramPoller.tryExtractDocumentText(file, "application/json"))
                .isEqualTo("{\"k\":1}");
    }

    @Test
    void extractTextReturnsNullForUnsupportedMime() throws Exception {
        Path file = tempDir.resolve("x.bin");
        Files.write(file, new byte[]{0, 1, 2});

        assertThat(TelegramPoller.tryExtractDocumentText(file, "application/octet-stream"))
                .isNull();
    }

    @Test
    void extractTextReturnsNullWhenFileExceedsCap() throws Exception {
        Path file = tempDir.resolve("big.txt");
        // 11 MB — above the 10 MB cap.
        Files.write(file, new byte[11 * 1024 * 1024]);

        assertThat(TelegramPoller.tryExtractDocumentText(file, "text/plain"))
                .isNull();
    }

    @Test
    void extractTextReturnsNullWhenFileDoesntExist() {
        assertThat(TelegramPoller.tryExtractDocumentText(
                tempDir.resolve("missing.txt"), "text/plain")).isNull();
    }

    @Test
    void tryTranscribeVoiceReturnsNullWhenWhisperUnavailable() {
        // Whisper is not installed in CI. Verifying the graceful-fallback
        // path matters more than the happy path (which is exercised manually).
        // Skip when whisper IS present — that's an integration test.
        Path fakeAudio = tempDir.resolve("clip.ogg");
        // Don't need to create real audio — just verify the command-probe falls through.
        ProcessBuilder probe = new ProcessBuilder("sh", "-c", "command -v whisper");
        try {
            Process p = probe.start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (p.exitValue() == 0) {
                // whisper IS available; skip this assertion.
                return;
            }
        } catch (Exception ignored) {
            // probe failed; continue to assert-null.
        }

        assertThat(TelegramPoller.tryTranscribeVoice(fakeAudio)).isNull();
    }
}
