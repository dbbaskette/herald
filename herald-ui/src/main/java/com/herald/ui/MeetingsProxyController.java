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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proxies the MeetingNotes backfill trigger from the UI (port 8080) to the bot
 * (port 8081). The bot loops the date range and enriches one meeting at a time;
 * this just forwards the request and relays the 202 acknowledgement.
 */
@RestController
@RequestMapping("/api/meetings")
class MeetingsProxyController {

    private final String botMeetingsUrl;
    private final HttpClient httpClient;

    MeetingsProxyController(@Value("${herald.bot.url:http://localhost:8081}") String botUrl) {
        this.botMeetingsUrl = botUrl + "/api/meetings";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @PostMapping(value = "/backfill", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> backfill(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "days", required = false) Integer days) {
        StringBuilder q = new StringBuilder();
        if (from != null && !from.isBlank()) q.append(q.isEmpty() ? '?' : '&').append("from=").append(from);
        if (to != null && !to.isBlank()) q.append(q.isEmpty() ? '?' : '&').append("to=").append(to);
        if (days != null) q.append(q.isEmpty() ? '?' : '&').append("days=").append(days);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(botMeetingsUrl + "/backfill" + q))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Bot unreachable: " + e.getMessage().replace("\"", "'") + "\"}");
        }
    }
}
