package com.herald.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.herald.agent.ModelSwitcher;

/**
 * Periodically checks the Claude OAuth token and refreshes the Anthropic ChatModel
 * if the token has been rotated in the keychain (by Claude Code CLI).
 *
 * <p>Only active when {@code herald.auth.oauth-enabled} is true, which is set
 * automatically by the startup logic in {@code HeraldApplication} when an OAuth
 * token is successfully loaded from the keychain.</p>
 */
@Component
@ConditionalOnProperty(name = "herald.auth.oauth-enabled", havingValue = "true")
public class OAuthTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(OAuthTokenRefresher.class);

    private final ClaudeOAuthService oauthService;
    private final ModelSwitcher modelSwitcher;
    private volatile String currentToken;

    public OAuthTokenRefresher(
            @Qualifier("anthropicChatModel") ChatModel verifyBean,
            ModelSwitcher modelSwitcher) {
        this.oauthService = new ClaudeOAuthService();
        this.modelSwitcher = modelSwitcher;

        // Initialize with the current token from keychain
        oauthService.loadFromKeychain();
        this.currentToken = oauthService.getAccessToken();

        if (oauthService.getSubscriptionType() != null) {
            log.info("Claude OAuth active — subscription: {}, expires: {}",
                    oauthService.getSubscriptionType(), oauthService.getExpiresAt());
        }
    }

    /**
     * Check every 30 minutes if the token needs refreshing.
     * Claude Code auto-refreshes tokens in the keychain, so we just re-read.
     */
    @Scheduled(fixedDelay = 1_800_000) // 30 minutes
    void checkAndRefreshToken() {
        if (!oauthService.isExpiredOrExpiring()) {
            return;
        }

        log.info("Claude OAuth token is expired or expiring soon, refreshing...");
        String newToken = oauthService.refreshFromKeychain();

        if (newToken == null) {
            log.warn("Could not refresh Claude OAuth token. "
                    + "Ensure Claude Code is running or run 'claude auth login' to re-authenticate.");
            return;
        }

        if (newToken.equals(currentToken)) {
            log.debug("Token unchanged after keychain re-read");
            return;
        }

        // Build a new AnthropicChatModel with the fresh token
        try {
            AnthropicApi freshApi = AnthropicApi.builder()
                    .apiKey(newToken)
                    .build();
            ChatModel freshModel = AnthropicChatModel.builder()
                    .anthropicApi(freshApi)
                    .build();
            modelSwitcher.updateProviderModel("anthropic", freshModel);
            currentToken = newToken;
            log.info("Anthropic ChatModel refreshed with new OAuth token (expires: {})",
                    oauthService.getExpiresAt());
        } catch (Exception e) {
            log.error("Failed to recreate AnthropicChatModel with refreshed token: {}", e.getMessage());
        }
    }
}
