package com.herald.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "herald")
public record HeraldConfig(Memory memory) {

    public record Memory(String dbPath) {
    }

    public String dbPath() {
        if (memory != null && memory.dbPath() != null) {
            return memory.dbPath();
        }
        return System.getProperty("user.home") + "/.herald/herald.db";
    }
}
