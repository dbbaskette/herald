package com.herald.ui;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class SkillCapabilities {
    record Snapshot(String status, Set<String> tools) {}
    private final URI uri;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    SkillCapabilities(@Value("${herald.ui.bot-port:8081}") int port) {
        uri = URI.create("http://localhost:" + port + "/api/skills/capabilities");
    }
    Snapshot current() {
        try {
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body().length() < 65536) {
                var data = JsonMapper.builder().build().readValue(response.body(), Map.class);
                if ("available".equals(data.get("status")) && data.get("tools") instanceof List<?> list
                        && list.stream().allMatch(String.class::isInstance)) {
                    return new Snapshot("available", list.stream().map(String.class::cast).collect(Collectors.toSet()));
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { /* Offline means unchecked, not every tool unavailable. */ }
        return new Snapshot("unavailable", Set.of());
    }
}
