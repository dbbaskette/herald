package com.herald.agent.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProfileParserTest {

    @Test
    void agentProfileRecordHoldsAllFields() {
        var profile = new AgentProfile(
                "cf-analyzer",
                "Analyzes Cloud Foundry environments",
                "sonnet",
                "anthropic",
                List.of("filesystem", "shell", "web"),
                null,   // skillsDirectory
                null,   // subagentsDirectory
                false,  // memory
                "./CONTEXT.md",
                200_000
        );

        assertThat(profile.name()).isEqualTo("cf-analyzer");
        assertThat(profile.description()).isEqualTo("Analyzes Cloud Foundry environments");
        assertThat(profile.model()).isEqualTo("sonnet");
        assertThat(profile.provider()).isEqualTo("anthropic");
        assertThat(profile.tools()).containsExactly("filesystem", "shell", "web");
        assertThat(profile.skillsDirectory()).isNull();
        assertThat(profile.subagentsDirectory()).isNull();
        assertThat(profile.memory()).isFalse();
        assertThat(profile.contextFile()).isEqualTo("./CONTEXT.md");
        assertThat(profile.maxTokens()).isEqualTo(200_000);
    }
}
