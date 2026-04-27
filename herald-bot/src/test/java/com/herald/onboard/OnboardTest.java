package com.herald.onboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.herald.onboard.TelegramOnboardingClient.OnboardingException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardTest {

    /**
     * Stub Telegram client that returns canned responses without touching the
     * network. The real client's regex parsing is exercised by
     * {@link TelegramOnboardingClientTest}.
     */
    private static class StubTelegram extends TelegramOnboardingClient {
        private final String username;
        private final Long chatId;
        private final OnboardingException error;

        StubTelegram(String username, Long chatId) {
            super();
            this.username = username;
            this.chatId = chatId;
            this.error = null;
        }

        StubTelegram(OnboardingException error) {
            super();
            this.username = null;
            this.chatId = null;
            this.error = error;
        }

        @Override
        public String validateToken(String token) throws OnboardingException {
            if (error != null) throw error;
            return username;
        }

        @Override
        public Long pollChatId(String token) throws OnboardingException {
            if (error != null) throw error;
            return chatId;
        }
    }

    @Test
    void writesAllValuesOnHappyPath(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");
        ScriptedPrompter p = new ScriptedPrompter(
                "sk-ant-key123",         // anthropic
                "1234:abc",              // bot token
                "",                      // press-enter to confirm message sent
                ""                       // memories dir — keep default
        );

        Onboard wizard = new Onboard(p, new StubTelegram("herald_bot", 987654321L), env);
        int code = wizard.run();

        assertThat(code).isZero();
        String written = Files.readString(env);
        assertThat(written)
                .contains("ANTHROPIC_API_KEY=sk-ant-key123")
                .contains("HERALD_TELEGRAM_BOT_TOKEN=1234:abc")
                .contains("HERALD_TELEGRAM_ALLOWED_CHAT_ID=987654321");
        assertThat(written).doesNotContain("HERALD_MEMORIES_DIR");

        String log = p.fullOutput();
        assertThat(log).contains("✓ Token valid — bot is @herald_bot");
        assertThat(log).contains("✓ Chat ID saved: 987654321");
    }

    @Test
    void skippingTelegramKeepsTaskAgentInstall(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");
        ScriptedPrompter p = new ScriptedPrompter(
                "sk-ant-key123",
                "",                      // skip telegram
                ""                       // skip memories
        );

        Onboard wizard = new Onboard(p, new StubTelegram("ignored", 0L), env);
        int code = wizard.run();

        assertThat(code).isZero();
        String written = Files.readString(env);
        assertThat(written).contains("ANTHROPIC_API_KEY=sk-ant-key123");
        assertThat(written).doesNotContain("HERALD_TELEGRAM_BOT_TOKEN");
    }

    @Test
    void invalidTokenSkipsTelegramSection(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");
        ScriptedPrompter p = new ScriptedPrompter(
                "sk-ant-key123",
                "bad-token",
                ""                       // skip memories
        );

        Onboard wizard = new Onboard(p,
                new StubTelegram(new OnboardingException("Telegram rejected the token.")),
                env);
        int code = wizard.run();

        assertThat(code).isZero();
        String written = Files.readString(env);
        assertThat(written).contains("ANTHROPIC_API_KEY=sk-ant-key123");
        assertThat(written).doesNotContain("HERALD_TELEGRAM_BOT_TOKEN");
        assertThat(p.fullOutput()).contains("✗ Telegram rejected the token.");
    }

    @Test
    void offersManualChatIdWhenAutoDetectFails(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");
        // ScriptedPrompter#waitForEnter is a no-op (doesn't consume an answer), so
        // the script doesn't need a placeholder for the press-Enter step.
        ScriptedPrompter p = new ScriptedPrompter(
                "sk-ant-key123",
                "1234:abc",
                "manual-chat-id-42",     // manual fallback after auto-detect fails
                ""                       // skip memories
        );

        // Stub returns null chatId — wizard polls 6× with a 1ms interval (test-only),
        // then falls back to the manual prompt.
        Onboard wizard = new Onboard(p, new StubTelegram("bot", null), env, 1L);
        int code = wizard.run();

        assertThat(code).isZero();
        assertThat(Files.readString(env)).contains("HERALD_TELEGRAM_ALLOWED_CHAT_ID=manual-chat-id-42");
    }

    @Test
    void emptyAnswersSkipsWriteEntirely(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");
        ScriptedPrompter p = new ScriptedPrompter("", "", "");

        Onboard wizard = new Onboard(p, new StubTelegram("ignored", 0L), env);
        int code = wizard.run();

        assertThat(code).isZero();
        assertThat(Files.exists(env)).isFalse();
        assertThat(p.fullOutput()).contains("Nothing to save.");
    }

    @Test
    void rerunMergesIntoExistingEnv(@TempDir Path tempDir) throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, """
                # Existing config
                HERALD_WEATHER_LOCATION=NYC
                ANTHROPIC_API_KEY=sk-ant-old
                """);

        ScriptedPrompter p = new ScriptedPrompter(
                "sk-ant-new",            // updates anthropic
                "",                      // skip telegram
                ""                       // skip memories
        );

        Onboard wizard = new Onboard(p, new StubTelegram("ignored", 0L), env);
        wizard.run();

        String written = Files.readString(env);
        assertThat(written)
                .contains("HERALD_WEATHER_LOCATION=NYC")  // preserved
                .contains("ANTHROPIC_API_KEY=sk-ant-new") // updated
                .contains("# Existing config");
        assertThat(written).doesNotContain("sk-ant-old");
    }

    @Test
    void parseAnswersExtractsKeyValuePairs() {
        var answers = Onboard.parseAnswers(new String[]{
                "--answer=anthropic:sk-ant-test",
                "--answer=bot-token:1234:abc:def",
                "--no-wait"
        });

        assertThat(answers).containsEntry("anthropic", "sk-ant-test");
        assertThat(answers).containsEntry("bot-token", "1234:abc:def");
        assertThat(answers).containsEntry("__skip_wait__", "1");
    }

    @Test
    void parseEnvPathExtractsOverride() {
        Path p = Onboard.parseEnvPath(new String[]{"--env=/tmp/foo.env"});
        assertThat(p).isEqualTo(Path.of("/tmp/foo.env"));
        assertThat(Onboard.parseEnvPath(new String[]{})).isNull();
    }
}
