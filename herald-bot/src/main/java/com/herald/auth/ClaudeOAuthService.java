package com.herald.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads Claude OAuth credentials from the macOS Keychain (stored by Claude Code CLI)
 * and provides the access token for use as an Anthropic API key.
 *
 * <p>Claude Code stores OAuth tokens under the keychain service "Claude Code-credentials".
 * The access token ({@code sk-ant-oat01-...}) works as a drop-in replacement for
 * {@code ANTHROPIC_API_KEY} in the {@code x-api-key} header. Tokens expire roughly every 12 hours;
 * Claude Code refreshes them automatically when running, so re-reading the keychain
 * picks up the fresh token.</p>
 */
public class ClaudeOAuthService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeOAuthService.class);
    private static final String KEYCHAIN_SERVICE = "Claude Code-credentials";
    private static final Duration EXPIRY_BUFFER = Duration.ofMinutes(30);

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile Instant expiresAt;
    private volatile String subscriptionType;

    /**
     * Attempt to load the Claude OAuth access token from the macOS Keychain.
     * Returns the token string if found, or {@code null} if unavailable.
     * Safe to call before Spring context is initialized.
     */
    public static String tryLoadTokenFromKeychain() {
        try {
            String json = readKeychainEntry();
            if (json == null) return null;

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode oauth = root.path("claudeAiOauth");

            if (oauth.isMissingNode() || !oauth.has("accessToken")) {
                log.debug("Keychain entry exists but has no claudeAiOauth.accessToken");
                return null;
            }

            String token = oauth.get("accessToken").asText();
            if (token == null || token.isBlank()) return null;

            // Check expiry
            if (oauth.has("expiresAt")) {
                long expiresAtMs = oauth.get("expiresAt").asLong();
                Instant expiry = Instant.ofEpochMilli(expiresAtMs);
                if (Instant.now().isAfter(expiry)) {
                    log.warn("Claude OAuth token from keychain is expired (expired at {})", expiry);
                    return null;
                }
                Duration remaining = Duration.between(Instant.now(), expiry);
                log.info("Claude OAuth token loaded from keychain (expires in {}h {}m)",
                        remaining.toHours(), remaining.toMinutesPart());
            }

            return token;
        } catch (Exception e) {
            log.debug("Failed to read Claude OAuth token from keychain: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Load full credentials (access token, refresh token, expiry) from keychain.
     * Returns true if successful.
     */
    public boolean loadFromKeychain() {
        try {
            String json = readKeychainEntry();
            if (json == null) return false;

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode oauth = root.path("claudeAiOauth");

            if (oauth.isMissingNode() || !oauth.has("accessToken")) return false;

            this.accessToken = oauth.get("accessToken").asText();
            this.refreshToken = oauth.has("refreshToken") ? oauth.get("refreshToken").asText() : null;
            this.expiresAt = oauth.has("expiresAt")
                    ? Instant.ofEpochMilli(oauth.get("expiresAt").asLong())
                    : null;
            this.subscriptionType = oauth.has("subscriptionType")
                    ? oauth.get("subscriptionType").asText()
                    : "unknown";

            return accessToken != null && !accessToken.isBlank();
        } catch (Exception e) {
            log.warn("Failed to load Claude OAuth credentials: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Try to refresh credentials by re-reading from the keychain.
     * Claude Code automatically refreshes tokens when running, so the keychain
     * usually has a valid token.
     *
     * @return the new access token, or null if refresh failed
     */
    public String refreshFromKeychain() {
        log.info("Refreshing Claude OAuth token from keychain...");
        if (loadFromKeychain()) {
            if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
                log.warn("Refreshed token is still expired. Run 'claude auth login' to re-authenticate.");
                return null;
            }
            log.info("Claude OAuth token refreshed successfully (subscription: {}, expires: {})",
                    subscriptionType, expiresAt);
            return accessToken;
        }
        return null;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getSubscriptionType() {
        return subscriptionType;
    }

    /**
     * Returns true if the token is expired or will expire within the buffer window.
     */
    public boolean isExpiredOrExpiring() {
        if (expiresAt == null) return false;
        return Instant.now().plus(EXPIRY_BUFFER).isAfter(expiresAt);
    }

    private static String readKeychainEntry() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            log.debug("Claude OAuth keychain reading is only supported on macOS");
            return null;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("security", "find-generic-password",
                    "-s", KEYCHAIN_SERVICE, "-w");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();

            if (exitCode != 0 || output.isBlank()) {
                log.debug("No Claude Code credentials found in keychain (exit code {})", exitCode);
                return null;
            }

            return output;
        } catch (IOException | InterruptedException e) {
            log.debug("Failed to read keychain: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
