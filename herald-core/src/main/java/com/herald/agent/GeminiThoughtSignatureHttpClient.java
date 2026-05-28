package com.herald.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * HttpClient wrapper that round-trips Gemini's {@code thought_signature} on
 * chat/completions calls. Gemini 3.x rejects assistant turns containing
 * {@code tool_calls} unless each tool_call carries the opaque signature
 * Google returned on the response that produced it. Spring AI's OpenAI
 * integration is unaware of the field; this wrapper bridges the gap.
 */
final class GeminiThoughtSignatureHttpClient implements HttpClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiThoughtSignatureHttpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CACHE_MAX = 4096;
    private static final String SSE_CONTENT_TYPE = "text/event-stream";

    private final HttpClient delegate;
    private final Map<String, String> signatureCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    GeminiThoughtSignatureHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        HttpRequest mutated = injectSignatures(request);
        HttpResponse response = delegate.execute(mutated, options);
        return captureSignatures(response);
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
        HttpRequest mutated = injectSignatures(request);
        return delegate.executeAsync(mutated, options).thenApply(this::captureSignatures);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private boolean isChatCompletions(HttpRequest request) {
        List<String> segs = request.pathSegments();
        int n = segs.size();
        return n >= 2 && "chat".equals(segs.get(n - 2)) && "completions".equals(segs.get(n - 1));
    }

    private HttpRequest injectSignatures(HttpRequest request) {
        if (!isChatCompletions(request) || request.body() == null) {
            return request;
        }
        byte[] bytes = readRequestBody(request.body());
        if (bytes == null) {
            return request;
        }
        try {
            JsonNode root = MAPPER.readTree(bytes);
            if (!root.isObject()) return request;
            JsonNode messages = root.get("messages");
            if (messages == null || !messages.isArray()) return request;

            boolean changed = false;
            int missing = 0;
            for (JsonNode msg : messages) {
                if (!msg.isObject() || !"assistant".equals(asText(msg, "role"))) continue;
                JsonNode toolCalls = msg.get("tool_calls");
                if (toolCalls == null || !toolCalls.isArray()) continue;
                for (JsonNode tc : toolCalls) {
                    if (!tc.isObject()) continue;
                    ObjectNode tcObj = (ObjectNode) tc;
                    if (hasSignature(tcObj)) continue;
                    String id = asText(tc, "id");
                    if (id == null) continue;
                    String sig = signatureCache.get(id);
                    if (sig == null) {
                        missing++;
                        continue;
                    }
                    ObjectNode extra = tcObj.has("extra_content") && tcObj.get("extra_content").isObject()
                            ? (ObjectNode) tcObj.get("extra_content")
                            : tcObj.putObject("extra_content");
                    ObjectNode google = extra.has("google") && extra.get("google").isObject()
                            ? (ObjectNode) extra.get("google")
                            : extra.putObject("google");
                    google.put("thought_signature", sig);
                    changed = true;
                }
            }
            if (missing > 0) {
                log.warn("Gemini request has {} assistant tool_call(s) without a known thought_signature — "
                        + "request may be rejected. Clear chat memory if this persists.", missing);
            }
            if (!changed) return request;
            byte[] newBytes = MAPPER.writeValueAsBytes(root);
            return request.toBuilder()
                    .body(new BufferedRequestBody(newBytes, request.body().contentType()))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to inject thought_signature into request: {}", e.toString());
            return request.toBuilder()
                    .body(new BufferedRequestBody(bytes, request.body().contentType()))
                    .build();
        }
    }

    private HttpResponse captureSignatures(HttpResponse response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            return response;
        }
        String contentType = response.headers().values("content-type").stream()
                .findFirst().orElse("");
        if (contentType.contains(SSE_CONTENT_TYPE)) {
            return response;
        }
        byte[] bytes;
        try (InputStream is = response.body()) {
            bytes = is.readAllBytes();
        } catch (IOException e) {
            log.warn("Failed to read Gemini response body for signature capture: {}", e.toString());
            return response;
        }
        try {
            JsonNode root = MAPPER.readTree(bytes);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray()) {
                for (JsonNode choice : choices) {
                    JsonNode msg = choice.get("message");
                    if (msg == null) continue;
                    JsonNode toolCalls = msg.get("tool_calls");
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            String id = asText(tc, "id");
                            String sig = extractSignature(tc);
                            if (id != null && sig != null) {
                                signatureCache.put(id, sig);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse Gemini response for signatures (non-fatal): {}", e.toString());
        }
        return new BufferedResponse(response.statusCode(), response.headers(),
                response.requestId(), bytes);
    }

    private static byte[] readRequestBody(HttpRequestBody body) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            body.writeTo(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to buffer outgoing request body: {}", e.toString());
            return null;
        }
    }

    private static boolean hasSignature(JsonNode tc) {
        JsonNode extra = tc.get("extra_content");
        if (extra == null) return false;
        JsonNode google = extra.get("google");
        return google != null && google.hasNonNull("thought_signature");
    }

    private static String extractSignature(JsonNode node) {
        JsonNode extra = node.get("extra_content");
        if (extra == null) return null;
        JsonNode google = extra.get("google");
        if (google == null) return null;
        JsonNode sig = google.get("thought_signature");
        return sig == null || sig.isNull() ? null : sig.asText();
    }

    private static String asText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static final class BufferedRequestBody implements HttpRequestBody {
        private final byte[] bytes;
        private final String contentType;

        BufferedRequestBody(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType;
        }

        @Override public void writeTo(OutputStream out) {
            try {
                out.write(bytes);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        @Override public String contentType() { return contentType; }
        @Override public long contentLength() { return bytes.length; }
        @Override public boolean repeatable() { return true; }
        @Override public void close() { }
    }

    private static final class BufferedResponse implements HttpResponse {
        private final int statusCode;
        private final Headers headers;
        private final Optional<String> requestId;
        private final byte[] body;

        BufferedResponse(int statusCode, Headers headers, Optional<String> requestId, byte[] body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.requestId = requestId;
            this.body = body;
        }

        @Override public int statusCode() { return statusCode; }
        @Override public Headers headers() { return headers; }
        @Override public Optional<String> requestId() { return requestId; }
        @Override public InputStream body() { return new ByteArrayInputStream(body); }
        @Override public void close() { }
    }
}
