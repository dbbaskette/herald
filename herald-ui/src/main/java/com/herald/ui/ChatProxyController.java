package com.herald.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/chat")
class ChatProxyController {

    private static final Logger log = LoggerFactory.getLogger(ChatProxyController.class);

    private final String botUrl;
    private final HttpClient httpClient;

    ChatProxyController(@Value("${herald.bot.url:http://localhost:8081}") String botUrl) {
        this.botUrl = botUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> chat(@RequestBody String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(botUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"reply\":null,\"error\":\"Bot unreachable: " +
                            e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /**
     * Proxies the bot's SSE chat stream. Opens a streaming HTTP connection upstream and
     * copies bytes to the servlet response so tokens flow through without buffering.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<StreamingResponseBody> stream(@RequestParam String message,
                                                  @RequestParam(name = "conversationId", required = false) String conversationId) {
        StringBuilder url = new StringBuilder(botUrl).append("/api/chat/stream?message=")
                .append(URLEncoder.encode(message, StandardCharsets.UTF_8));
        if (conversationId != null && !conversationId.isBlank()) {
            url.append("&conversationId=").append(URLEncoder.encode(conversationId, StandardCharsets.UTF_8));
        }

        StreamingResponseBody body = output -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body()) {
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        output.write(buf, 0, n);
                        output.flush();
                    }
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Chat stream proxy interrupted: {}", e.getMessage());
                String err = "event: error\ndata: Bot unreachable: "
                        + (e.getMessage() != null ? e.getMessage().replace("\n", " ") : "") + "\n\n";
                try {
                    output.write(err.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } catch (IOException ignored) {
                    // Client already gone
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }
}
