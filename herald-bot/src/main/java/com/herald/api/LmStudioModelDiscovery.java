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
 * Discovers the models in a local LM Studio server and keeps Herald pointed at
 * whatever model is actually loaded there.
 *
 * <p>Prefers LM Studio's native {@code GET /api/v0/models} (which reports each
 * model's {@code state} and {@code capabilities}) over the OpenAI-compatible
 * {@code /v1/models} (which lists every downloaded model — embeddings, audio,
 * unloaded LLMs — with no load state). From the native endpoint we can: list
 * only tool-capable chat models, find the one that's loaded, make it the
 * default, and — if LM Studio is the active provider — switch the agent onto it
 * so a model swapped in LM Studio is the one Herald actually uses.</p>
 *
 * <p>Runs at startup (swap + restart picks it up) and on demand via
 * {@code POST /api/model/rescan}. No-ops when LM Studio isn't configured.</p>
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

    /** Discovered chat models plus which one LM Studio currently has loaded. */
    public record Discovery(List<String> chatModels, String loaded) {
        boolean isEmpty() { return chatModels.isEmpty(); }
    }

    /**
     * Query LM Studio for its chat models and the loaded one. Best-effort: returns
     * an empty result on any failure, so a missing LM Studio never breaks startup
     * or the rescan endpoint. Falls back to {@code /v1/models} when the native
     * endpoint isn't available (older LM Studio).
     */
    public Discovery discover() {
        if (!isEnabled()) return new Discovery(List.of(), null);
        try {
            Discovery native_ = discoverNative();
            if (!native_.isEmpty()) return native_;
        } catch (java.net.http.HttpTimeoutException te) {
            // LM Studio is busy (often mid-generation on a large model). Bail
            // rather than also waiting out a timeout on /v1 — keep the current
            // catalog; the next rescan (or an idle moment) will pick it up.
            log.warn("LM Studio model discovery timed out (server busy?) — keeping current catalog");
            return new Discovery(List.of(), null);
        } catch (Exception e) {
            log.debug("LM Studio native query failed ({}) — trying /v1/models", e.getMessage());
        }
        return discoverOpenAi();
    }

    /** Native endpoint: rich metadata (state + capabilities). Throws on failure. */
    private Discovery discoverNative() throws Exception {
        JsonNode data = getJson(hostRoot() + "/api/v0/models").path("data");
        List<String> chat = new ArrayList<>();
        String loaded = null;
        for (JsonNode m : data) {
            String id = m.path("id").asText("");
            if (id.isBlank()) continue;
            String type = m.path("type").asText("");
            boolean toolUse = false;
            for (JsonNode c : m.path("capabilities")) {
                if ("tool_use".equals(c.asText())) { toolUse = true; break; }
            }
            boolean isLoaded = "loaded".equals(m.path("state").asText(""));
            boolean isChat = ("llm".equals(type) || "vlm".equals(type)) && toolUse;
            if (isChat && !chat.contains(id)) chat.add(id);
            if (isLoaded) {
                loaded = id;
                if (!chat.contains(id)) chat.add(id); // surface the loaded one even if metadata is thin
            }
        }
        return new Discovery(orderLoadedFirst(chat, loaded), loaded);
    }

    /** OpenAI-compatible fallback: ids only, no load state. */
    private Discovery discoverOpenAi() {
        String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
        try {
            JsonNode data = getJson(url).path("data");
            List<String> chat = new ArrayList<>();
            for (JsonNode m : data) {
                String id = m.path("id").asText("");
                // Can't tell chat from embedding/audio here; drop obvious non-chat ids.
                if (!id.isBlank() && !id.startsWith("text-embedding") && !chat.contains(id)) {
                    chat.add(id);
                }
            }
            chat.sort(String.CASE_INSENSITIVE_ORDER);
            return new Discovery(chat, null);
        } catch (Exception e) {
            log.warn("LM Studio model discovery failed ({}): {}", url, e.getMessage());
            return new Discovery(List.of(), null);
        }
    }

    private JsonNode getJson(String url) throws Exception {
        // 8s tolerates a busy LM Studio (mid-generation on a large model can stall
        // the model-list endpoint for a few seconds); normal latency is < 10ms.
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        return MAPPER.readTree(res.body());
    }

    private static List<String> orderLoadedFirst(List<String> models, String loaded) {
        List<String> rest = new ArrayList<>(models);
        rest.sort(String.CASE_INSENSITIVE_ORDER);
        if (loaded == null) return rest;
        List<String> out = new ArrayList<>();
        out.add(loaded);
        for (String m : rest) if (!m.equals(loaded)) out.add(m);
        return out;
    }

    /** Strip a trailing {@code /v1} so we can reach the native {@code /api/v0} routes. */
    private String hostRoot() {
        String u = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (u.endsWith("/v1")) u = u.substring(0, u.length() - 3);
        return u;
    }

    /**
     * Discover and apply: refresh the catalog, make the loaded model the default,
     * and — if LM Studio is the active provider — switch the agent onto the loaded
     * model so Herald uses what's actually loaded. Returns the discovery result.
     */
    public Discovery rescan() {
        Discovery d = discover();
        if (d.isEmpty()) return d;
        if (d.loaded() != null) {
            modelSwitcher.setProviderDefault("lmstudio", d.loaded());
        }
        modelSwitcher.setProviderModels("lmstudio", d.chatModels());
        if (d.loaded() != null && "lmstudio".equals(modelSwitcher.getActiveProvider())
                && !d.loaded().equals(modelSwitcher.getActiveModel())) {
            log.info("LM Studio loaded model changed — switching agent to {}", d.loaded());
            modelSwitcher.switchModel("lmstudio", d.loaded());
        }
        return d;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!isEnabled()) return;
        Discovery d = rescan();
        log.info("LM Studio: {} chat model(s) discovered, loaded={}", d.chatModels().size(), d.loaded());
    }
}
