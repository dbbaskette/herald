package com.herald.config;

import com.herald.agent.ModelProviderConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class ModelProviderCapabilityTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ModelProviderConfig.class, Config.class);
    @Configuration(proxyBeanMethods = false) @EnableConfigurationProperties(HeraldConfig.class) static class Config {}

    @ParameterizedTest @CsvSource({"openai,api-key,fixture", "gemini,api-key,fixture",
            "ollama,base-url,http://localhost:11434", "lmstudio,base-url,http://localhost:1234"})
    void onlyConfiguredProviderCreatesClientWithoutNetwork(String provider, String field, String value) {
        runner.withPropertyValues("herald.providers." + provider + "." + field + "=" + value)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean(provider + "ChatModel");
                    for (var other : ProviderCapabilities.ORDER) if (!other.equals(provider))
                        assertThat(context).doesNotHaveBean(other + "ChatModel");
                });
    }
    @Test void blanksAndMalformedUrlsCreateNoProviderClients() {
        runner.withPropertyValues("herald.providers.openai.api-key=  ", "herald.providers.gemini.api-key=  ",
                "herald.providers.ollama.base-url= ", "herald.providers.lmstudio.base-url=file:///tmp/model")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    for (var provider : ProviderCapabilities.ORDER) assertThat(context).doesNotHaveBean(provider + "ChatModel");
                });
    }
}
