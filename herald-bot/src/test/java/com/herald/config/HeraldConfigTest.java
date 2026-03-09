package com.herald.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldConfigTest {

    @Test
    void dbPathDefaultsToHomeDirectory() {
        HeraldConfig config = new HeraldConfig(null);
        assertThat(config.dbPath()).isEqualTo(
                System.getProperty("user.home") + "/.herald/herald.db");
    }

    @Test
    void dbPathUsesConfiguredValue() {
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory("/custom/path/herald.db"));
        assertThat(config.dbPath()).isEqualTo("/custom/path/herald.db");
    }
}
