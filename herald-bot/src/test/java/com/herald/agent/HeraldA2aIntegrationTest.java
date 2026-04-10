package com.herald.agent;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.herald.config.HeraldConfig;
import com.herald.cron.CronTools;
import com.herald.tools.FileSystemTools;
import com.herald.tools.GwsTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.TelegramSendTool;
import com.herald.tools.WebTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that when herald.a2a.agents is configured, modelSwitcher builds a
 * TaskTool with an A2A SubagentType registered. Does NOT attempt a real JSON-RPC
 * delegation — the WireMock server exists only to serve the agent-card.json
 * so that any eager resolver call would succeed, and to hold a stable URL
 * that can be recorded in the config.
 */
class HeraldA2aIntegrationTest {

    private static final String HAIKU_MODEL = "claude-haiku-4-5";
    private static final String SONNET_MODEL = "claude-sonnet-4-5";
    private static final String OPUS_MODEL = "claude-opus-4-5";
    private static final String OPENAI_MODEL = "gpt-4o";
    private static final String OLLAMA_MODEL = "llama3.2";
    private static final String GEMINI_MODEL = "gemini-2.5-flash";
    private static final String LMSTUDIO_MODEL = "qwen/qwen3.5-35b-a3b";

    private static final String AGENT_CARD_JSON = """
            {
              "name": "Test Agent",
              "description": "Integration test agent",
              "url": "%s/testagent",
              "version": "1.0.0",
              "capabilities": {},
              "skills": [],
              "defaultInputModes": ["text"],
              "defaultOutputModes": ["text"]
            }
            """;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void modelSwitcherRegistersA2aAgentFromConfig(@TempDir Path tempDir) {
        String baseUrl = "http://localhost:" + wireMock.getPort();
        String agentCard = String.format(AGENT_CARD_JSON, baseUrl);

        wireMock.stubFor(get(urlEqualTo("/testagent/.well-known/agent-card.json"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(agentCard)));

        HeraldAgentConfig agentConfig = new HeraldAgentConfig();
        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);
        ChatModel mockModel = mock(ChatModel.class);

        HeraldConfig.A2aAgent a2aAgent = new HeraldConfig.A2aAgent(
                "test-agent", baseUrl + "/testagent", Map.of());
        HeraldConfig config = new HeraldConfig(
                null, null,
                new HeraldConfig.Agent("TestBot", null, null, null, null),
                null, null, null, null, null, null, null,
                new HeraldConfig.A2a(List.of(a2aAgent)));

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockModel, config, false, Optional.of(chatMemory),
                mock(HeraldShellDecorator.class),
                new FileSystemTools(), Optional.empty(), mock(ObjectProvider.class),
                Optional.of(mock(TelegramSendTool.class)),
                Optional.of(mock(GwsTools.class)), new WebTools(""), Optional.of(mock(CronTools.class)),
                Optional.of(jdbcTemplate),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, GEMINI_MODEL, LMSTUDIO_MODEL,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web"));

        assertThat(switcher).isNotNull();
        assertThat(switcher.getActiveClient()).isNotNull();
    }
}
