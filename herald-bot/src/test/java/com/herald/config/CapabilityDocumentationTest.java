package com.herald.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CapabilityDocumentationTest {

    private static String repositoryFile(String relativePath) throws IOException {
        return Files.readString(Path.of("..", relativePath));
    }

    @Test
    void capabilityMatrixCoversEverySupportedProviderAndOptionalSwitch() throws IOException {
        String matrix = repositoryFile("docs/provider-capabilities.md");

        assertThat(matrix).contains("Anthropic", "OpenAI", "Gemini", "Ollama", "LM Studio");
        assertThat(matrix).contains(
                "HERALD_PERSISTENCE_ENABLED",
                "HERALD_CRON_ENABLED",
                "HERALD_MEETINGNOTES_ENABLED",
                "HERALD_GOOGLE_ENABLED",
                "HERALD_REMINDERS_ENABLED",
                "HERALD_A2A_CLIENT_ENABLED",
                "HERALD_A2A_SERVER_ENABLED");
        assertThat(matrix).containsIgnoringCase("precedence");
        assertThat(matrix).containsIgnoringCase("fallback");
        assertThat(matrix).containsIgnoringCase("restart");
    }

    @Test
    void externalConfigurationExampleUsesTheBoundHeraldSchema() throws IOException {
        String example = repositoryFile("herald.yaml.example");

        assertThat(example).contains("herald:", "default-provider:", "bot-token:", "db-path:");
        assertThat(example).doesNotContain("allowed_chat_id:", "api_key:", "base_url:");
    }

    @Test
    void meetingRecoveryDocumentsTheMasterEnableSwitch() throws IOException {
        assertThat(repositoryFile("docs/integrations/meeting-recovery.md"))
                .contains("HERALD_MEETINGNOTES_ENABLED=true", "defaults to `false`");
    }
}
