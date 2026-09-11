package com.herald.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.*;

class GenAiManifestExampleTest {
    @Test @SuppressWarnings("unchecked") void bindingExamplePreservesPrivateSingleInstanceBoundary() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.exists(root.resolve("manifest.yml"))) root = root.getParent();
        String source = Files.readString(root.resolve("manifest.yml"));
        Map<String, Object> manifest = new Yaml().load(source);
        var apps = (List<Map<String, Object>>) manifest.get("applications");
        assertThat(apps).hasSize(1);
        var app = apps.getFirst();
        assertThat(app.get("instances")).isEqualTo(1);
        assertThat(app.get("path")).isEqualTo("herald-bot/target/herald-bot-0.4.1-SNAPSHOT.jar");
        assertThat(Files.isRegularFile(root.resolve("herald-bot/pom.xml"))).isTrue();
        assertThat(app.get("routes")).isEqualTo(List.of(Map.of("route", "herald-bot.apps.internal")));
        assertThat(app.get("services")).isEqualTo(List.of("((genai-service))"));
        var env = (Map<String, Object>) app.get("env");
        assertThat(env).containsEntry("JBP_CONFIG_OPEN_JDK_JRE", "{ jre: { version: 21.+ } }")
                .containsEntry("HERALD_GENAI_BINDING_SERVICE_NAME", "((genai-service))")
                .containsEntry("HERALD_GENAI_BINDING_MODEL", "((genai-model))");
        assertThat(env.keySet()).noneMatch(key -> key.contains("API_KEY") || key.contains("TOKEN"));
        assertThat(env).doesNotContainKey("SPRING_PROFILES_ACTIVE");
        assertThat(source).doesNotContain("api-key:", "api_key:", "random-route:");
    }
}
