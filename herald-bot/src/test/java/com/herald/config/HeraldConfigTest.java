package com.herald.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldConfigTest {

    @Test
    void dbPathDefaultsToTildePath() {
        HeraldConfig config = new HeraldConfig(null);
        assertThat(config.dbPath()).isEqualTo("~/.herald/herald.db");
    }

    @Test
    void dbPathUsesConfiguredValue() {
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory("/custom/path/herald.db"));
        assertThat(config.dbPath()).isEqualTo("/custom/path/herald.db");
    }

    @Test
    void dbPathUsesConfiguredTildePath() {
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory("~/custom/herald.db"));
        assertThat(config.dbPath()).isEqualTo("~/custom/herald.db");
    }
}
