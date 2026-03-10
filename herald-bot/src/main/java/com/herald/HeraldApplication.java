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

    // TODO: When spring-ai-agent-utils stubs are replaced, migrate to canonical versions:
    //  - ShellTools
    //  - FileSystemTools (currently stub)
    //  - TodoWriteTool (currently stub)
    //  - AskUserQuestionTool
    // TaskTool is now wired via HeraldAgentConfig

    public static void main(String[] args) {
        SpringApplication.run(HeraldApplication.class, args);
    }
}
