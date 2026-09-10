package com.herald.doctor;

import com.herald.config.ProviderCapabilities;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DiagnosticEnvironmentTest {
    @Test void springFileJsonAndCommandLineUseStartupPrecedence(@TempDir Path dir) throws Exception {
        var config = dir.resolve("fixture.yaml");
        Files.writeString(config, "herald:\n  agent:\n    default-provider: openai\n  providers:\n    openai:\n      api-key: fixture-key\n    ollama:\n      base-url: http://localhost:11434\n");
        var args = new String[]{"--spring.config.location=" + config.toUri()};
        assertThat(ProviderCapabilities.resolve(DiagnosticEnvironment.load(args, Map.of())).effectiveProvider()).isEqualTo("openai");
        var env = Map.of("SPRING_APPLICATION_JSON", "{\"herald\":{\"agent\":{\"default-provider\":\"ollama\"}}}");
        assertThat(ProviderCapabilities.resolve(DiagnosticEnvironment.load(args, env)).effectiveProvider()).isEqualTo("ollama");
        assertThat(ProviderCapabilities.resolve(DiagnosticEnvironment.load(new String[]{args[0], "--herald.agent.default-provider=openai"}, env))
                .effectiveProvider()).isEqualTo("openai");
    }
}
