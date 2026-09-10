package com.herald.mcp;

import java.util.List;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpToolConfiguration {
    @Bean
    McpToolRegistry mcpToolRegistry(@Value("${spring.ai.mcp.client.enabled:false}") boolean enabled,
            org.springframework.core.env.Environment environment,
            ObjectProvider<SyncMcpToolCallbackProvider> providers) {
        List<String> allowed = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("herald.mcp.allowed-tools", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                .orElse(List.of());
        return new McpToolRegistry(enabled, allowed.stream().map(String::trim).filter(s -> !s.isEmpty()).toList(),
                () -> providers.orderedStream().toList());
    }
}
