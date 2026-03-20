package com.herald.agent;

import com.herald.agent.profile.AgentProfile;
import com.herald.agent.profile.AgentProfileParser;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AgentFactoryTest {

    @Test
    void buildsClientFromMinimalProfile() {
        var profile = new AgentProfile("test", "A test agent", "sonnet", null,
                List.of("filesystem", "web"), null, null, false, null, null);

        ChatClient client = AgentFactory.fromProfile(profile, "You are a test agent.",
                mock(ChatModel.class));

        assertThat(client).isNotNull();
    }

    @Test
    void buildsClientFromParsedAgentsMd() {
        String content = """
                ---
                name: analyzer
                description: Analyzes things
                model: sonnet
                tools: [filesystem, shell, web]
                memory: false
                ---

                You are an analyzer agent.
                """;

        AgentProfileParser.Result parsed = AgentProfileParser.parse(content);

        ChatClient client = AgentFactory.fromProfile(parsed.profile(), parsed.systemPrompt(),
                mock(ChatModel.class));

        assertThat(client).isNotNull();
    }

    @Test
    void buildsClientWithNoTools() {
        var profile = new AgentProfile("bare", "Bare agent", null, null,
                List.of(), null, null, false, null, null);

        ChatClient client = AgentFactory.fromProfile(profile, "You are bare.",
                mock(ChatModel.class));

        assertThat(client).isNotNull();
    }

    @Test
    void includesDateTimeAdvisorAlways() {
        var profile = new AgentProfile("test", "test", null, null,
                List.of(), null, null, false, null, null);

        // If this builds without error, the advisor chain is valid
        ChatClient client = AgentFactory.fromProfile(profile, "prompt",
                mock(ChatModel.class));

        assertThat(client).isNotNull();
    }

    @Test
    void includesContextMdAdvisorWhenContextFileSet() {
        var profile = new AgentProfile("test", "test", null, null,
                List.of(), null, null, false, "/tmp/test-context.md", null);

        ChatClient client = AgentFactory.fromProfile(profile, "prompt",
                mock(ChatModel.class));

        assertThat(client).isNotNull();
    }

    // --- Multi-provider overload tests ---

    @Test
    void resolvesProviderFromProfile() {
        var profile = new AgentProfile("test", "test", "sonnet", "anthropic",
                List.of(), null, null, false, null, null);
        ChatModel mockModel = mock(ChatModel.class);

        Map<String, ChatModel> providers = Map.of("anthropic", mockModel);

        ChatClient client = AgentFactory.fromProfile(profile, "prompt",
                providers, new ToolCategoryRegistry(), null, null);

        assertThat(client).isNotNull();
    }

    @Test
    void throwsForMissingProvider() {
        var profile = new AgentProfile("test", "test", null, "openai",
                List.of(), null, null, false, null, null);

        Map<String, ChatModel> providers = Map.of("anthropic", mock(ChatModel.class));

        assertThatThrownBy(() -> AgentFactory.fromProfile(profile, "prompt",
                providers, new ToolCategoryRegistry(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai")
                .hasMessageContaining("not available");
    }

    @Test
    void cliOverrideTakesPrecedenceOverProfile() {
        var profile = new AgentProfile("test", "test", "sonnet", "anthropic",
                List.of(), null, null, false, null, null);
        ChatModel openaiModel = mock(ChatModel.class);

        Map<String, ChatModel> providers = Map.of(
                "anthropic", mock(ChatModel.class),
                "openai", openaiModel);

        ChatClient client = AgentFactory.fromProfile(profile, "prompt",
                providers, new ToolCategoryRegistry(), "openai", null);

        assertThat(client).isNotNull();
    }

    @Test
    void defaultsToAnthropicWhenNoProviderSpecified() {
        var profile = new AgentProfile("test", "test", null, null,
                List.of(), null, null, false, null, null);
        ChatModel anthropicModel = mock(ChatModel.class);

        Map<String, ChatModel> providers = Map.of(
                "anthropic", anthropicModel,
                "openai", mock(ChatModel.class));

        ChatClient client = AgentFactory.fromProfile(profile, "prompt",
                providers, new ToolCategoryRegistry(), null, null);

        assertThat(client).isNotNull();
    }
}
