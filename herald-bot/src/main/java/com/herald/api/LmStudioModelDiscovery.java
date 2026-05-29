package com.herald.api;

import com.herald.agent.ModelSwitcher;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Discovers the models available in a local LM Studio server via its
 * OpenAI-compatible {@code GET /models} endpoint and feeds them into
 * {@link ModelSwitcher}'s catalog, so the UI model picker reflects whatever is
 * actually loaded in LM Studio.
 *
 * <p>Runs once at startup (so swapping a model in LM Studio + restarting the bot
 * picks it up) and on demand via {@code POST /api/model/rescan} (so swapping a
 * model needs no restart at all). No-ops when LM Studio isn't configured.</p>
 */
@Component
public class LmStudioModelDiscovery {

    private static final Logger log = LoggerFactory.getLogger(LmStudioModelDiscovery.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final ModelSwitcher modelSwitcher;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public LmStudioModelDiscovery(
            @Value("${herald.providers.lmstudio.base-url:}") String baseUrl,
            ModelSwitcher modelSwitcher) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.modelSwitcher = modelSwitcher;
    }

    public boolean isEnabled() {
        return !baseUrl.isBlank();
    }

    /**
     * Query LM Studio for its model ids. Best-effort: returns an empty list on
     * any failure (server down, unreachable, malformed response) — never throws,
     * so a missing LM Studio never breaks startup or the rescan endpoint.
     */
    public List<String> discover() {
        if (!isEnabled()) return List.of();
        String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("LM Studio model discovery: {} returned HTTP {}", url, res.statusCode());
                return List.of();
            }
            JsonNode data = MAPPER.readTree(res.body()).path("data");
            List<String> ids = new ArrayList<>();
            for (JsonNode m : data) {
                String id = m.path("id").asText("");
                if (!id.isBlank() && !ids.contains(id)) ids.add(id);
            }
            ids.sort(String.CASE_INSENSITIVE_ORDER);
            return ids;
        } catch (Exception e) {
            log.warn("LM Studio model discovery failed ({}): {}", url, e.getMessage());
            return List.of();
        }
    }

    /**
     * Discover and push the result into the model catalog. Returns the discovered
     * ids (empty if LM Studio is unreachable, in which case the catalog is left
     * as-is rather than wiped).
     */
    public List<String> rescan() {
        List<String> ids = discover();
        if (!ids.isEmpty()) {
            modelSwitcher.setProviderModels("lmstudio", ids);
        }
        return ids;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!isEnabled()) return;
        List<String> ids = rescan();
        log.info("LM Studio: discovered {} model(s) at startup", ids.size());
    }
}
