package com.herald.agent.subagent;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HeraldSubagentFactoryTest {

    @Test
    void buildReturnsSubagentTypeWithRegisteredModels() {
        ChatModel mockModel = mock(ChatModel.class);

        SubagentType result = HeraldSubagentFactory.builder()
                .chatClientBuilder("default", ChatClient.builder(mockModel))
                .chatClientBuilder("fast", ChatClient.builder(mockModel))
                .build();

        assertThat(result).isNotNull();
        assertThat(result.resolver()).isNotNull();
        assertThat(result.executor()).isNotNull();
    }

    @Test
    void buildWithSingleModelSucceeds() {
        ChatModel mockModel = mock(ChatModel.class);

        SubagentType result = HeraldSubagentFactory.builder()
                .chatClientBuilder("default", ChatClient.builder(mockModel))
                .build();

        assertThat(result).isNotNull();
    }
}
