package com.herald.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldConfigTest {

    @Test
    void dbPathDefaultsToTildePath() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);
        assertThat(config.dbPath()).isEqualTo("~/.herald/herald.db");
    }

    @Test
    void dbPathUsesConfiguredValue() {
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory("/custom/path/herald.db"), null, null, null, null, null, null, null, null, null);
        assertThat(config.dbPath()).isEqualTo("/custom/path/herald.db");
    }

    @Test
    void dbPathUsesConfiguredTildePath() {
        HeraldConfig config = new HeraldConfig(
                new HeraldConfig.Memory("~/custom/herald.db"), null, null, null, null, null, null, null, null, null);
        assertThat(config.dbPath()).isEqualTo("~/custom/herald.db");
    }

    @Test
    void personaDefaultsWhenAgentIsNull() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);
        assertThat(config.persona()).startsWith("Herald");
    }

    @Test
    void personaUsesConfiguredValue() {
        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("Custom Persona", null, null, null, null, null, null), null, null, null, null, null, null, null);
        assertThat(config.persona()).isEqualTo("Custom Persona");
    }

    @Test
    void systemPromptExtraDefaultsToEmpty() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);
        assertThat(config.systemPromptExtra()).isEmpty();
    }

    @Test
    void systemPromptExtraUsesConfiguredValue() {
        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent(null, "Extra instructions", null, null, null, null, null), null, null, null, null, null, null, null);
        assertThat(config.systemPromptExtra()).isEqualTo("Extra instructions");
    }

    @Test
    void contextFileDefaultsToTildePath() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);
        assertThat(config.contextFile()).isEqualTo("~/.herald/CONTEXT.md");
    }

    @Test
    void contextFileUsesConfiguredValue() {
        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent(null, null, "/custom/CONTEXT.md", null, null, null, null), null, null, null, null, null, null, null);
        assertThat(config.contextFile()).isEqualTo("/custom/CONTEXT.md");
    }

    @Test
    void weatherLocationDefaultsToEmptyWhenNull() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);
        assertThat(config.weatherLocation()).isEmpty();
    }

    @Test
    void weatherLocationUsesConfiguredValue() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null,
                new HeraldConfig.Weather("New+York"), null, null, null, null);
        assertThat(config.weatherLocation()).isEqualTo("New+York");
    }

    @Test
    void memoriesDirDefaultsToTildePath() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);
        assertThat(config.memoriesDir()).isEqualTo("~/.herald/memories");
    }

    @Test
    void memoriesDirUsesConfiguredValue() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null,
                new HeraldConfig.LongTermMemory("/custom/memories"));
        assertThat(config.memoriesDir()).isEqualTo("/custom/memories");
    }

    @Test
    void weatherLocationDefaultsToEmptyWhenBlank() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null,
                new HeraldConfig.Weather("  "), null, null, null, null);
        assertThat(config.weatherLocation()).isEmpty();
    }

    // --- Phase E: Obsidian vault mode ---

    @Test
    void obsidianVaultModePreferenceDefaultsToAuto() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);
        assertThat(config.obsidianVaultModePreference()).isEqualTo("auto");
    }

    @Test
    void obsidianVaultModePreferenceNormalizesCase() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null,
                new HeraldConfig.Obsidian(null, "ON"), null, null, null);
        assertThat(config.obsidianVaultModePreference()).isEqualTo("on");
    }

    @Test
    void obsidianVaultModePreferenceRejectsInvalidValues() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null,
                new HeraldConfig.Obsidian(null, "yes-please"), null, null, null);
        assertThat(config.obsidianVaultModePreference()).isEqualTo("auto");
    }

    @Test
    void resolveObsidianVaultModeOffIsAlwaysDisabled() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null,
                new HeraldConfig.Obsidian("/vault", "off"), null, null, null);
        assertThat(config.resolveObsidianVaultMode("/vault/memories")).isFalse();
    }

    @Test
    void resolveObsidianVaultModeOnIsAlwaysEnabled() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null,
                new HeraldConfig.Obsidian(null, "on"), null, null, null);
        assertThat(config.resolveObsidianVaultMode("/anywhere")).isTrue();
    }

    @Test
    void resolveObsidianVaultModeAutoEnabledWhenPathsOverlap() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null,
                new HeraldConfig.Obsidian("/Users/dan/Vault", "auto"), null, null, null);
        assertThat(config.resolveObsidianVaultMode("/Users/dan/Vault/Herald-Memory")).isTrue();
        // Reverse containment (memories dir is a parent) also counts.
        assertThat(config.resolveObsidianVaultMode("/Users/dan")).isTrue();
    }

    @Test
    void resolveObsidianVaultModeAutoDisabledWhenPathsDisjoint() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null,
                new HeraldConfig.Obsidian("/Users/dan/Vault", "auto"), null, null, null);
        assertThat(config.resolveObsidianVaultMode("/Users/dan/OtherDir")).isFalse();
    }

    @Test
    void resolveObsidianVaultModeAutoDisabledWhenNoVaultPath() {
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);
        assertThat(config.resolveObsidianVaultMode("/Users/dan/.herald/memories")).isFalse();
    }

    @Test
    void resolveObsidianVaultModeExpandsTildeInPaths() {
        String home = System.getProperty("user.home");
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null,
                new HeraldConfig.Obsidian("~/Documents/Vault", "auto"), null, null, null);
        assertThat(config.resolveObsidianVaultMode(home + "/Documents/Vault/Herald")).isTrue();
        assertThat(config.resolveObsidianVaultMode("~/Documents/Vault/Herald")).isTrue();
    }
}
