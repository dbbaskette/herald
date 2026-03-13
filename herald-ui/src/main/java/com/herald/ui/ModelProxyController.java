package com.herald.ui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model")
class ModelProxyController {

    private final String botModelUrl;
    private final HttpClient httpClient;

    ModelProxyController(@Value("${herald.bot.url:http://localhost:8081}") String botUrl) {
        this.botModelUrl = botUrl + "/api/model";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> status() {
        return proxy("GET", null);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> switchModel(@RequestBody String body) {
        return proxy("POST", body);
    }

    private ResponseEntity<String> proxy(String method, String body) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(botModelUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10));

            HttpRequest request = "POST".equals(method)
                    ? builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
                    : builder.GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Bot unreachable: " +
                            e.getMessage().replace("\"", "'") + "\"}");
        }
    }
}
