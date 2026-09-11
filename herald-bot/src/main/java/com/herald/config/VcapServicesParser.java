package com.herald.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Optional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded adapter for tagged Tanzu AI Services bindings. Never includes input in failures. */
public final class VcapServicesParser {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private VcapServicesParser() {}

    public record Binding(String baseUrl, String apiKey, String model) {
        @Override public String toString() { return "GenAiBinding[redacted]"; }
    }

    public static Optional<Binding> parse(String input, String serviceName, String explicitModel) {
        boolean selecting = hasText(serviceName);
        if (!hasText(input)) {
            if (selecting) throw failure("SERVICE_SELECTION", "herald.genai.binding.service-name");
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = JSON.readTree(input);
        } catch (RuntimeException invalid) {
            // Jackson's messages, source snippets and causes can all contain credentials.
            throw failure("INVALID_JSON", "VCAP_SERVICES");
        }
        if (root == null || !root.isObject()) throw failure("INVALID_STRUCTURE", "VCAP_SERVICES");
        var candidates = new ArrayList<JsonNode>();
        for (JsonNode services : root) {
            if (!services.isArray()) throw failure("INVALID_STRUCTURE", "VCAP_SERVICES");
            for (JsonNode service : services) {
                if (!service.isObject()) throw failure("INVALID_STRUCTURE", "VCAP_SERVICES");
                JsonNode tags = service.path("tags");
                if (tags.isMissingNode()) continue;
                if (!tags.isArray()) throw failure("INVALID_STRUCTURE", "tags");
                boolean candidate = false;
                for (JsonNode tag : tags) {
                    if (!tag.isString()) throw failure("INVALID_STRUCTURE", "tags");
                    candidate |= "genai".equals(tag.asText()) || "llm".equals(tag.asText());
                }
                if (candidate) candidates.add(service);
            }
        }
        if (selecting) {
            candidates.removeIf(service -> {
                JsonNode name = service.has("instance_name") ? service.get("instance_name") : service.path("name");
                return !name.isString() || !serviceName.trim().equals(name.asText());
            });
            if (candidates.size() != 1) throw failure("SERVICE_SELECTION", "herald.genai.binding.service-name");
        } else if (candidates.size() > 1) {
            throw failure("MULTIPLE_BINDINGS", "herald.genai.binding.service-name");
        }
        if (candidates.isEmpty()) return Optional.empty();
        JsonNode credentials = candidates.getFirst().path("credentials");
        if (!credentials.isObject()) throw failure("INVALID_CREDENTIALS", "credentials");
        if (credentials.has("wire_format") && !"openai".equals(string(credentials, "wire_format"))) {
            throw failure("INCOMPATIBLE_FORMAT", "wire_format");
        }
        boolean single = credentials.has("api_base") || credentials.has("api_key") || credentials.has("model_name");
        if (single) {
            String base = endpoint(string(credentials, "api_base"));
            String key = string(credentials, "api_key");
            String model = string(credentials, "model_name");
            if (!"openai".equals(string(credentials, "wire_format"))) throw failure("INCOMPATIBLE_FORMAT", "wire_format");
            JsonNode capabilities = credentials.path("model_capabilities");
            if (!capabilities.isMissingNode()) {
                if (!capabilities.isArray()) throw failure("INVALID_CREDENTIALS", "model_capabilities");
                boolean embedding = false;
                boolean chat = false;
                for (JsonNode capability : capabilities) {
                    if (!capability.isString()) throw failure("INVALID_CREDENTIALS", "model_capabilities");
                    embedding |= "embedding".equals(capability.asText()) || "embeddings".equals(capability.asText());
                    chat |= "chat".equals(capability.asText());
                }
                if (embedding && !chat) throw failure("EMBEDDING_ONLY", "model_capabilities");
            }
            return Optional.of(new Binding(base.endsWith("/v1") ? base : base + "/v1", key, model));
        }
        JsonNode endpoint = credentials.path("endpoint");
        if (!endpoint.isObject()) throw failure("INVALID_CREDENTIALS", "endpoint");
        String base = endpoint(string(endpoint, "api_base"));
        String key = string(endpoint, "api_key");
        if (!hasText(explicitModel)) throw failure("MODEL_REQUIRED", "herald.genai.binding.model");
        return Optional.of(new Binding(base + "/openai/v1", key, explicitModel.trim()));
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || !hasText(value.asText())) throw failure("INVALID_CREDENTIALS", field);
        return value.asText().trim();
    }

    private static String endpoint(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw failure("INVALID_ENDPOINT", "api_base");
        }
        return value.replaceAll("/+$", "");
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    static IllegalStateException failure(String code, String field) {
        return new IllegalStateException("GENAI_" + code + ": check " + field
                + "; correct the binding or set herald.genai.binding.enabled=false for local configuration.");
    }
}
