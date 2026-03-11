package com.herald;

import com.herald.auth.ClaudeOAuthService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class,
        org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration.class
})
@ConfigurationPropertiesScan
@EnableScheduling
public class HeraldApplication {

    public static void main(String[] args) {
        // Try Claude OAuth (Claude Pro/Max subscription) before Spring context starts.
        // If a valid token is found in the macOS Keychain, it replaces ANTHROPIC_API_KEY.
        String oauthToken = ClaudeOAuthService.tryLoadTokenFromKeychain();
        if (oauthToken != null) {
            System.setProperty("spring.ai.anthropic.api-key", oauthToken);
            System.setProperty("herald.providers.anthropic.api-key", oauthToken);
            System.setProperty("herald.auth.oauth-enabled", "true");
            System.out.println("[Herald] Using Claude OAuth token from macOS Keychain");
        } else {
            String apiKey = System.getenv("ANTHROPIC_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) {
                System.out.println("[Herald] Using ANTHROPIC_API_KEY from environment");
            } else {
                System.err.println("[Herald] WARNING: No Anthropic API key found. "
                        + "Set ANTHROPIC_API_KEY or run 'claude auth login' to authenticate via OAuth.");
            }
        }

        SpringApplication.run(HeraldApplication.class, args);
    }
}
