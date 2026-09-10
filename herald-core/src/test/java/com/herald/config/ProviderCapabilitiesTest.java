package com.herald.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ProviderCapabilitiesTest {
    @ParameterizedTest @CsvSource({"anthropic,api-key,fixture", "openai,api-key,fixture", "gemini,api-key,fixture",
            "ollama,base-url,http://localhost:11434", "lmstudio,base-url,http://localhost:1234"})
    void providerOnlyUsesItsRealRequirements(String provider, String setting, String value) {
        var resolution = ProviderCapabilities.resolve(provider, Map.of("herald.providers." + provider + "." + setting, value)::get);
        assertThat(resolution.effectiveProvider()).isEqualTo(provider);
        assertThat(resolution.fallback()).isFalse();
        assertThat(resolution.toString()).doesNotContain("fixture", "http://");
    }
    @Test void blankCredentialsAndInvalidLocalUrlCannotBeSelected() {
        var resolution = ProviderCapabilities.resolve("ollama", Map.of("herald.providers.ollama.base-url", "file:///tmp/model",
                "herald.providers.openai.api-key", "  ")::get);
        assertThat(resolution.usable()).isFalse();
        assertThat(resolution.providers()).filteredOn(p -> p.id().equals("ollama"))
                .extracting(CapabilityStatus::state).containsExactly(CapabilityState.FAILED);
    }
    @Test void normalizesRequestedProviderAndReportsDeterministicFallback() {
        var values = Map.of("herald.providers.openai.api-key", "fixture", "herald.providers.gemini.api-key", "fixture");
        assertThat(ProviderCapabilities.resolve(" GEMINI ", values::get).effectiveProvider()).isEqualTo("gemini");
        var fallback = ProviderCapabilities.resolve("anthropic", values::get);
        assertThat(fallback.effectiveProvider()).isEqualTo("openai");
        assertThat(fallback.fallback()).isTrue();
    }
    @Test void noTelegramOrTaskModeIsValidAndDoesNotExposeCredentials() {
        var environment = new MockEnvironment();
        assertThat(ProviderCapabilities.telegram(environment).state()).isEqualTo(CapabilityState.UNCONFIGURED);
        environment.withProperty("herald.telegram.bot-token", "private-token").withProperty("herald.telegram.allowed-chat-id", "42");
        assertThat(ProviderCapabilities.telegram(environment).state()).isEqualTo(CapabilityState.HEALTHY);
        assertThat(ProviderCapabilities.telegram(environment).toString()).doesNotContain("private-token");
        environment.withProperty("agents", "fixture.md");
        assertThat(ProviderCapabilities.telegram(environment).state()).isEqualTo(CapabilityState.DISABLED);
    }
}
