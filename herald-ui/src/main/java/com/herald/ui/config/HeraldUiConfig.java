package com.herald.ui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("herald.ui")
public record HeraldUiConfig(
        @DefaultValue("~/.herald/herald.db") String dbPath,
        @DefaultValue("~/.herald/skills") String skillsPath) {
}
