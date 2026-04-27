package com.herald.onboard;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import com.herald.onboard.TelegramOnboardingClient.OnboardingException;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spins up a tiny in-process HTTP server to validate the regex-based response
 * parsing — Telegram's wire format is stable, but we want a guard rail in case
 * we ever need to re-tweak the patterns.
 */
class TelegramOnboardingClientTest {

    private HttpServer server;
    private TelegramOnboardingClient client;
    private String responseBody = "";
    private int responseStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new TelegramOnboardingClient(HttpClient.newHttpClient(), baseUrl);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void validateTokenReturnsUsername() throws Exception {
        responseBody = """
                {"ok":true,"result":{"id":123,"is_bot":true,"username":"my_test_bot"}}
                """;

        String username = client.validateToken("test-token");

        assertThat(username).isEqualTo("my_test_bot");
    }

    @Test
    void validateTokenThrowsOnOkFalse() {
        responseBody = """
                {"ok":false,"error_code":401,"description":"Unauthorized"}
                """;

        assertThatThrownBy(() -> client.validateToken("bad"))
                .isInstanceOf(OnboardingException.class)
                .hasMessageContaining("rejected the token");
    }

    @Test
    void validateTokenThrowsOn401() {
        responseStatus = 401;
        responseBody = "Unauthorized";

        assertThatThrownBy(() -> client.validateToken("bad"))
                .isInstanceOf(OnboardingException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void pollChatIdExtractsIdFromGetUpdates() throws Exception {
        responseBody = """
                {"ok":true,"result":[{"update_id":1,"message":{"message_id":1,
                "from":{"id":111,"is_bot":false},
                "chat":{"id":987654321,"first_name":"Test","type":"private"},
                "date":1700000000,"text":"hi"}}]}
                """;

        Long chatId = client.pollChatId("test-token");

        assertThat(chatId).isEqualTo(987654321L);
    }

    @Test
    void pollChatIdHandlesNegativeIds() throws Exception {
        responseBody = """
                {"ok":true,"result":[{"update_id":1,"message":{
                "chat":{"id":-1001234567890,"type":"supergroup"}}}]}
                """;

        Long chatId = client.pollChatId("test-token");

        assertThat(chatId).isEqualTo(-1001234567890L);
    }

    @Test
    void pollChatIdReturnsNullWhenNoUpdates() throws Exception {
        responseBody = "{\"ok\":true,\"result\":[]}";

        Long chatId = client.pollChatId("test-token");

        assertThat(chatId).isNull();
    }
}
