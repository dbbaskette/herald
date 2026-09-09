package com.herald.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeminiThoughtSignatureHttpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void capturesSignatureAndInjectsItIntoTheNextToolCallRequest() throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.execute(any(), any())).thenReturn(response("""
                {"choices":[{"message":{"tool_calls":[{"id":"call_1","extra_content":{"google":{"thought_signature":"opaque-signature"}}}]}}]}
                """));
        var client = new GeminiThoughtSignatureHttpClient(delegate);

        client.execute(request("{\"messages\":[]}"), RequestOptions.none()).close();
        client.execute(request("""
                {"messages":[{"role":"assistant","tool_calls":[{"id":"call_1","type":"function"}]}]}
                """), RequestOptions.none()).close();

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(delegate, org.mockito.Mockito.times(2))
                .execute(requestCaptor.capture(), any());
        JsonNode forwarded = MAPPER.readTree(bodyBytes(requestCaptor.getAllValues().get(1).body()));
        assertThat(forwarded.at("/messages/0/tool_calls/0/extra_content/google/thought_signature").asText())
                .isEqualTo("opaque-signature");
    }

    private static HttpRequest request(String json) {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .baseUrl("https://example.invalid")
                .addPathSegments("chat", "completions")
                .body(body(json))
                .build();
    }

    private static HttpRequestBody body(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new HttpRequestBody() {
            @Override public void writeTo(OutputStream out) { try { out.write(bytes); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } }
            @Override public String contentType() { return "application/json"; }
            @Override public long contentLength() { return bytes.length; }
            @Override public boolean repeatable() { return true; }
            @Override public void close() { }
        };
    }

    private static byte[] bodyBytes(HttpRequestBody body) {
        var out = new ByteArrayOutputStream();
        body.writeTo(out);
        return out.toByteArray();
    }

    private static HttpResponse response(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new HttpResponse() {
            @Override public int statusCode() { return 200; }
            @Override public Headers headers() { return Headers.builder().put("content-type", "application/json").build(); }
            @Override public Optional<String> requestId() { return Optional.of("fixture-request"); }
            @Override public InputStream body() { return new ByteArrayInputStream(bytes); }
            @Override public void close() { }
        };
    }
}
