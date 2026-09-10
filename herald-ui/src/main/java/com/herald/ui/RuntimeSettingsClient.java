package com.herald.ui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Narrow HTTP contract; this process never guesses the bot's environment. */
@Component
class RuntimeSettingsClient {
    record EffectiveSetting(String effective, String source, String environmentVariable) {}
    private final URI uri;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    RuntimeSettingsClient(@Value("${herald.bot.url:http://localhost:8081}") String botUrl) {
        uri = URI.create(botUrl + "/api/runtime/settings");
    }
    Map<String, EffectiveSetting> snapshot() {
        try {
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Map.of();
            Map<String, EffectiveSetting> values = JsonMapper.builder().build().readValue(response.body(), new TypeReference<>() {});
            return values == null ? Map.of() : values;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception unavailable) { return Map.of(); }
    }
}
