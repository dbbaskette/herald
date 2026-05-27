package com.herald.ui;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approvals")
class ApprovalProxyController {

    private final String botApprovalsUrl;
    private final HttpClient httpClient;

    ApprovalProxyController(@Value("${herald.bot.url:http://localhost:8081}") String botUrl) {
        this.botApprovalsUrl = botUrl + "/api/approvals";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> list(
            @RequestParam(name = "conversationId", required = false) String conversationId) {
        String url = botApprovalsUrl;
        if (conversationId != null && !conversationId.isBlank()) {
            url += "?conversationId=" + URLEncoder.encode(conversationId, StandardCharsets.UTF_8);
        }
        return proxy("GET", url, null);
    }

    @PostMapping(value = "/{id}/resolve",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> resolve(@PathVariable String id, @RequestBody String body) {
        return proxy("POST", botApprovalsUrl + "/" + id + "/resolve", body);
    }

    private ResponseEntity<String> proxy(String method, String url, String body) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30));

            HttpRequest request = "POST".equals(method)
                    ? builder.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : "{}"))
                            .build()
                    : builder.GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Bot unreachable: "
                            + e.getMessage().replace("\"", "'") + "\"}");
        }
    }
}
