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
        // `--doctor` short-circuits the Spring boot path and runs the diagnostic
        // battery. Keeps doctor startup under a second so `run.sh doctor` feels
        // like the terminal CLI it pretends to be, not a slow JVM.
        for (String arg : args) {
            if ("--doctor".equals(arg)) {
                com.herald.doctor.Doctor.main(stripArg(args, "--doctor"));
                return;
            }
        }

        // Preflight (#283): friendly one-line errors for common misconfig before
        // Spring boots, so we never surface a 40-line BeanCreationException for
        // things like "no API key" or "JDK 17". On any fatal issue, prints the
        // hint + docs link and exits 1.
        com.herald.doctor.Preflight.runOrExit(args);

        SpringApplication.run(HeraldApplication.class, args);
    }

    private static String[] stripArg(String[] args, String toRemove) {
        return java.util.Arrays.stream(args)
                .filter(a -> !toRemove.equals(a))
                .toArray(String[]::new);
    }
}
