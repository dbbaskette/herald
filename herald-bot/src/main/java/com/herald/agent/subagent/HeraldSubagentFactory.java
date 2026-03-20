package com.herald.agent.subagent;

import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Model-agnostic factory for building a {@link SubagentType} with named
 * ChatClient tiers.  Internally delegates to the upstream builder while
 * keeping Herald's public API free of provider-specific class names.
 */
public final class HeraldSubagentFactory {

    private HeraldSubagentFactory() {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final ClaudeSubagentType.Builder delegate = ClaudeSubagentType.builder();

        Builder() {}

        public Builder chatClientBuilder(String name, ChatClient.Builder clientBuilder) {
            delegate.chatClientBuilder(name, clientBuilder);
            return this;
        }

        public SubagentType build() {
            return delegate.build();
        }
    }
}
