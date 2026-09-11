package com.herald.doctor;

import com.herald.config.HeraldConfig;
import com.herald.config.ProviderCapabilities;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class GenAiBindingDiagnosticTest {
    @Test void bindingAndLocalConfigurationUseSameResolver(@TempDir Path dir) throws Exception {
        var yaml = dir.resolve("application.yaml");
        Files.writeString(yaml, """
                herald:
                  agent:
                    default-provider: openai
                    model:
                      openai: yaml-model
                  providers:
                    openai:
                      api-key: ${OPENAI_API_KEY:yaml-key}
                      base-url: https://local.example.test
                """);
        String[] args = {"--spring.config.location=" + yaml.toUri()};
        for (var env : java.util.List.of(Map.<String,String>of(), Map.of("OPENAI_API_KEY", "env-key"),
                Map.of("HERALD_PROVIDERS_OPENAI_API_KEY", "env-key"))) {
            var local = DiagnosticEnvironment.load(args, env);
            assertThat(local.getProperty("herald.providers.openai.api-key")).isEqualTo(env.isEmpty() ? "yaml-key" : "env-key");
            assertThat(local.getProperty("herald.agent.model.openai")).isEqualTo("yaml-model");
        }
        String binding;
        try (var input = getClass().getResourceAsStream("/genai/single-model.json")) {
            binding = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var bound = DiagnosticEnvironment.load(args, Map.of("VCAP_SERVICES", binding, "OPENAI_API_KEY", "env-key"));
        var config = Binder.get(bound).bind("herald", HeraldConfig.class).get();
        assertThat(config.providers().openai().apiKey()).isEqualTo("synthetic-secret");
        assertThat(ProviderCapabilities.resolve(config)).isEqualTo(ProviderCapabilities.resolve(bound));
        assertThat(bound.getProperty("herald.agent.model.openai")).isEqualTo("test/chat-model");
        var disabled = DiagnosticEnvironment.load(args, Map.of("VCAP_SERVICES", "invalid synthetic-secret", "HERALD_GENAI_BINDING_ENABLED", "false"));
        assertThat(disabled.getProperty("herald.providers.openai.api-key")).isEqualTo("yaml-key");
    }

    @Test void malformedBindingHasNoSecretInOutputOrCause(CapturedOutput output) {
        var error = catchThrowable(() -> DiagnosticEnvironment.load(new String[]{}, Map.of("VCAP_SERVICES", "{synthetic-secret")));
        assertThat(error).isInstanceOf(IllegalStateException.class).hasNoCause();
        var trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString() + output.getAll()).contains("GENAI_INVALID_JSON").doesNotContain("synthetic-secret");
    }
}
