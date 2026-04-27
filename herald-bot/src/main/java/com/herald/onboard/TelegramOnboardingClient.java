package com.herald.onboard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny Telegram Bot API wrapper used only by the onboarding wizard. Wraps two
 * endpoints — {@code getMe} (token validation) and {@code getUpdates} (chat-id
 * autodetection) — using the JDK {@link HttpClient}. We don't pull in the full
 * pengrad client here because the wizard runs before Spring boots and we want
 * to keep cold-start under a second.
 *
 * <p>Response parsing is regex-based by design: the two fields we extract
 * ({@code "ok": true} and the integer chat id) are stable and self-contained,
 * so a 50-line regex parser is the right tool. Anything more complex (Telegram
 * webhook payloads, etc.) belongs in the runtime bot, not the wizard.</p>
 */
public class TelegramOnboardingClient {

    /** Pattern to extract the {@code chat.id} integer from a getUpdates result. */
    private static final Pattern CHAT_ID = Pattern.compile("\"chat\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*(-?\\d+)");
    private static final Pattern OK_TRUE = Pattern.compile("\"ok\"\\s*:\\s*true");
    private static final Pattern USERNAME = Pattern.compile("\"username\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient http;
    private final String baseUrl;

    public TelegramOnboardingClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                "https://api.telegram.org");
    }

    public TelegramOnboardingClient(HttpClient http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /**
     * Calls {@code getMe} to confirm the token is valid and return the bot's
     * @username. Throws {@link OnboardingException} with a user-friendly
     * message on failure (network, HTTP error, or {@code "ok": false}).
     */
    public String validateToken(String token) throws OnboardingException {
        String body = call(token, "getMe");
        if (!OK_TRUE.matcher(body).find()) {
            throw new OnboardingException("Telegram rejected the token. "
                    + "Double-check you copied the full string from @BotFather.");
        }
        Matcher m = USERNAME.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Fetches the most recent update and returns the sender's chat id.
     * Returns {@code null} if no updates have arrived yet — the caller should
     * prompt the user to send a message and retry.
     */
    public Long pollChatId(String token) throws OnboardingException {
        String body = call(token, "getUpdates?limit=1&offset=-1");
        if (!OK_TRUE.matcher(body).find()) {
            throw new OnboardingException("Telegram returned an error fetching updates: " + body);
        }
        Matcher m = CHAT_ID.matcher(body);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    private String call(String token, String method) throws OnboardingException {
        String url = baseUrl + "/bot" + token + "/" + method;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 404) {
                throw new OnboardingException("Telegram rejected the token (HTTP "
                        + response.statusCode() + "). The token is wrong or the bot was deleted.");
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new OnboardingException("Network error talking to Telegram: " + e.getMessage());
        }
    }

    /** Carries a wizard-friendly message — the wizard prints it and continues. */
    public static class OnboardingException extends Exception {
        public OnboardingException(String message) {
            super(message);
        }
    }
}
