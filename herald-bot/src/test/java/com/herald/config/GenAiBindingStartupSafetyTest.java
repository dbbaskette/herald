package com.herald.config;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class GenAiBindingStartupSafetyTest {
    @Configuration(proxyBeanMethods = false)
    static class Application {}

    private SpringApplication application(Map<String, String> values) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(values)));
        var app = new SpringApplication(Application.class);
        app.setEnvironment(environment);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        return app;
    }

    @Test void malformedCfInputCannotLeakBeforeAdapterRuns(CapturedOutput output) {
        var app = application(Map.of("VCAP_APPLICATION", "{}", "VCAP_SERVICES", "{synthetic-secret"));
        var error = catchThrowable(() -> app.run("--spring.config.location=classpath:/application.yaml"));
        assertThat(error).isNotNull();
        var trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString() + output.getAll()).contains("GENAI_INVALID_JSON").doesNotContain("synthetic-secret");
    }

    @Test void disabledBindingBypassesCfParserToo(CapturedOutput output) {
        var app = application(Map.of("VCAP_APPLICATION", "{}", "VCAP_SERVICES", "{synthetic-secret",
                "HERALD_GENAI_BINDING_ENABLED", "false"));
        try (var context = app.run("--spring.config.location=classpath:/application.yaml")) {
            assertThat(context.getEnvironment().getProperty("herald.agent.default-provider")).isEqualTo("anthropic");
        }
        assertThat(output.getAll()).doesNotContain("synthetic-secret", "Could not parse VCAP_SERVICES");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"OPENAI_API_KEY", "HERALD_PROVIDERS_OPENAI_API_KEY"})
    void registeredAdapterMapsBindingWithoutCreatingApplicationBeans(String keyAlias) throws Exception {
        var app = application(Map.of("VCAP_APPLICATION", "{}", "VCAP_SERVICES", VcapServicesParserTest.fixture("single-model"),
                keyAlias, "local-key", "HERALD_PROVIDERS_OPENAI_BASE_URL", "https://local.example.test/v1",
                "HERALD_MODEL_OPENAI", "local-model", "HERALD_AGENT_MODEL_CATALOG_OPENAI", "local-model,other-model",
                "HERALD_DEFAULT_PROVIDER", "ollama"));
        try (var context = app.run("--spring.config.location=classpath:/application.yaml")) {
            assertThat(context.getEnvironment().getProperty("herald.providers.openai.api-key")).isEqualTo("synthetic-secret");
            assertThat(context.getEnvironment().getProperty("herald.agent.model.openai")).isEqualTo("test/chat-model");
            assertThat(context.getEnvironment().getProperty("herald.agent.default-provider")).isEqualTo("openai");
            assertThat(context.getEnvironment().getProperty("herald.providers.openai.base-url")).isEqualTo("https://models.example.test/team/openai/v1");
            assertThat(context.getEnvironment().getProperty("herald.agent.model-catalog.openai")).isEqualTo("test/chat-model");
            var config = org.springframework.boot.context.properties.bind.Binder.get(context.getEnvironment())
                    .bind("herald", HeraldConfig.class).get();
            assertThat(ProviderCapabilities.resolve(config).effectiveProvider()).isEqualTo("openai");
            assertThat(ProviderCapabilities.resolve(config)).isEqualTo(ProviderCapabilities.resolve(context.getEnvironment()));
        }
    }
}
