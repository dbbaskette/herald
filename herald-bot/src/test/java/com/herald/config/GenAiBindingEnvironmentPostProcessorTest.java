package com.herald.config;

import com.herald.agent.ModelProviderConfig;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.assertj.core.api.Assertions.*;

class GenAiBindingEnvironmentPostProcessorTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HeraldConfig.class)
    @Import(ModelProviderConfig.class)
    static class Application {}

    public static StandardEnvironment environment(Map<String, String> values) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(values)));
        return environment;
    }

    @Test void registeredProcessorRunsBeforeBindingAndConditions() throws Exception {
        var environment = environment(Map.of("VCAP_SERVICES", VcapServicesParserTest.fixture("single-model"),
                "OPENAI_API_KEY", "local-secret", "HERALD_PROVIDERS_OPENAI_BASE_URL", "https://local.example.test",
                "HERALD_MODEL_OPENAI", "local-model", "HERALD_DEFAULT_PROVIDER", "ollama",
                "HERALD_AGENT_MODEL_CATALOG_OPENAI", "local-one,local-two"));
        var application = new SpringApplication(Application.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        try (var context = application.run("--spring.config.location=classpath:/application.yaml")) {
            HeraldConfig config = context.getBean(HeraldConfig.class);
            assertThat(config.providers().openai().apiKey()).isEqualTo("synthetic-secret");
            assertThat(config.providers().openai().baseUrl()).isEqualTo("https://models.example.test/team/openai/v1");
            assertThat(config.defaultProvider()).isEqualTo("openai");
            assertThat(ProviderCapabilities.resolve(config).effectiveProvider()).isEqualTo("openai");
            assertThat(ProviderCapabilities.resolve(environment).effectiveProvider()).isEqualTo("openai");
            assertThat(context.containsBean("openaiChatModel")).isTrue();
            assertThat(environment.getProperty("herald.agent.model.openai")).isEqualTo("test/chat-model");
            assertThat(environment.getProperty("herald.agent.model-catalog.openai")).isEqualTo("test/chat-model");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"HERALD_GENAI_BINDING_ENABLED", "herald.genai.binding.enabled"})
    void disablingBypassesMalformedInput(String control) {
        var environment = environment(Map.of(control, "false", "VCAP_SERVICES", "synthetic-secret invalid",
                "herald.providers.openai.api-key", "local-secret"));
        GenAiBindingEnvironmentPostProcessor.applyTo(environment);
        assertThat(environment.getProperty("herald.providers.openai.api-key")).isEqualTo("local-secret");
    }

    @ParameterizedTest @ValueSource(strings = {"HERALD_GENAI_BINDING_MODEL", "herald.genai.binding.model"})
    void endpointModelControls(String control) throws Exception {
        var environment = environment(Map.of(control, "selected-model", "VCAP_SERVICES", VcapServicesParserTest.fixture("endpoint-only")));
        GenAiBindingEnvironmentPostProcessor.applyTo(environment);
        assertThat(environment.getProperty("herald.agent.model.openai")).isEqualTo("selected-model");
    }

    @ParameterizedTest @ValueSource(strings = {"HERALD_GENAI_BINDING_SERVICE_NAME", "herald.genai.binding.service-name"})
    void serviceSelectionControls(String control) throws Exception {
        var environment = environment(Map.of(control, "chosen", "VCAP_SERVICES", VcapServicesParserTest.fixture("multiple")));
        GenAiBindingEnvironmentPostProcessor.applyTo(environment);
        assertThat(environment.getProperty("herald.providers.openai.api-key")).isEqualTo("synthetic-secret");
    }

    @Test void malformedBooleanIsSanitized() {
        var environment = environment(Map.of("HERALD_GENAI_BINDING_ENABLED", "synthetic-secret"));
        assertThatThrownBy(() -> GenAiBindingEnvironmentPostProcessor.applyTo(environment))
                .hasMessageContaining("GENAI_INVALID_SETTING").hasMessageNotContaining("synthetic-secret").hasNoCause();
    }
}
