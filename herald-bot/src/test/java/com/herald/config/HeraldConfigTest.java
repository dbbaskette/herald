package com.herald.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldConfigTest {

    @Test
    void dbPathDefaultsToTildePath() {
        HeraldConfig config = new HeraldConfig(null, null, null, null);
        assertThat(config.dbPath()).isEqualTo("~/.herald/herald.db");
    }

    @Test
    void dbPathUsesConfiguredValue() {
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory("/custom/path/herald.db"), null, null, null);
        assertThat(config.dbPath()).isEqualTo("/custom/path/herald.db");
    }

    @Test
    void dbPathUsesConfiguredTildePath() {
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory("~/custom/herald.db"), null, null, null);
        assertThat(config.dbPath()).isEqualTo("~/custom/herald.db");
    }

    @Test
    void personaDefaultsWhenAgentIsNull() {
        HeraldConfig config = new HeraldConfig(null, null, null, null);
        assertThat(config.persona()).startsWith("Herald");
    }

    @Test
    void personaUsesConfiguredValue() {
        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("Custom Persona", null), null);
        assertThat(config.persona()).isEqualTo("Custom Persona");
    }

    @Test
    void systemPromptExtraDefaultsToEmpty() {
        HeraldConfig config = new HeraldConfig(null, null, null, null);
        assertThat(config.systemPromptExtra()).isEmpty();
    }

    @Test
    void systemPromptExtraUsesConfiguredValue() {
        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent(null, "Extra instructions"), null);
        assertThat(config.systemPromptExtra()).isEqualTo("Extra instructions");
    }
}
