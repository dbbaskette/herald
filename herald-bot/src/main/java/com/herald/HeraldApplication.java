package com.herald;

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
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            System.out.println("[Herald] Using ANTHROPIC_API_KEY from environment");
        } else {
            System.err.println("[Herald] WARNING: No ANTHROPIC_API_KEY set. "
                    + "Run 'claude setup-token' and add the token to .env");
        }

        SpringApplication.run(HeraldApplication.class, args);
    }
}
