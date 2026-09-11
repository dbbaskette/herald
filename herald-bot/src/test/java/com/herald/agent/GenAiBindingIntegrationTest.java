package com.herald.agent;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.herald.config.GenAiBindingEnvironmentPostProcessor;
import com.herald.config.HeraldConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class GenAiBindingIntegrationTest {
    @ParameterizedTest @ValueSource(strings = {"single", "single-versioned", "endpoint", "local"})
    void completionAndStreamUseSelectedEndpointKeyAndModel(String mode) {
        var server = new WireMockServer(0);
        server.start();
        try {
            String base = server.baseUrl() + "/tenant/deployment";
            String credentials = mode.equals("endpoint")
                    ? "\"endpoint\":{\"api_base\":\"" + base + "/\",\"api_key\":\"bound-key\"}"
                    : "\"api_base\":\"" + base + "/openai" + (mode.equals("single-versioned") ? "/v1/" : "/")
                        + "\",\"api_key\":\"bound-key\",\"model_name\":\"bound-model\",\"wire_format\":\"openai\"";
            var environment = new StandardEnvironment();
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                    "VCAP_SERVICES", mode.equals("local") ? "{}" : "{\"renamed\":[{\"tags\":[\"llm\"],\"credentials\":{" + credentials + "}}]}",
                    "herald.providers.openai.base-url", base + "/openai/v1",
                    "herald.providers.openai.api-key", "local-key",
                    "herald.agent.default-provider", "openai",
                    "herald.agent.model.openai", "local-model",
                    "herald.genai.binding.model", "bound-model")));
            GenAiBindingEnvironmentPostProcessor.applyTo(environment);
            var config = Binder.get(environment).bind("herald", HeraldConfig.class).get();
            var model = new ModelProviderConfig().openaiChatModel(config);
            String expectedModel = mode.equals("local") ? "local-model" : "bound-model";
            String expectedKey = mode.equals("local") ? "local-key" : "bound-key";
            String path = "/tenant/deployment/openai/v1/chat/completions";
            server.stubFor(post(urlEqualTo(path)).willReturn(okJson("""
                    {"id":"completion-1","object":"chat.completion","created":1,"model":"%s",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"PONG"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """.formatted(expectedModel))));
            var prompt = new Prompt("ping", OpenAiChatOptions.builder().model(environment.getProperty("herald.agent.model.openai")).build());
            assertThat(model.call(prompt).getResult().getOutput().getText()).isEqualTo("PONG");
            server.stubFor(post(urlEqualTo(path)).atPriority(1).withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                    .willReturn(ok("""
                            data: {"id":"stream-1","object":"chat.completion.chunk","created":1,"model":"%s","choices":[{"index":0,"delta":{"role":"assistant","content":"PONG"},"finish_reason":null}]}

                            data: {"id":"stream-1","object":"chat.completion.chunk","created":1,"model":"%s","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                            data: [DONE]

                            """.formatted(expectedModel, expectedModel)).withHeader("Content-Type", "text/event-stream")));
            var chunks = model.stream(prompt).collectList().block(Duration.ofSeconds(10));
            assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.getResult().getOutput().getText()).isEqualTo("PONG"));
            server.verify(2, postRequestedFor(urlEqualTo(path))
                    .withHeader("Authorization", equalTo("Bearer " + expectedKey))
                    .withRequestBody(matchingJsonPath("$.model", equalTo(expectedModel))));
        } finally {
            server.stop();
        }
    }
}
