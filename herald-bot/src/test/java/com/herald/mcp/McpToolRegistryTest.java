package com.herald.mcp;

import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpToolRegistryTest {
    ToolCallback callback(String name) {
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description("fixture").inputSchema("{}").build());
        return callback;
    }
    @Test void disabledAndEmptyAllowlistDoNotResolveProviders() {
        java.util.function.Supplier<List<ToolCallbackProvider>> providers = mock(java.util.function.Supplier.class);
        assertThat(new McpToolRegistry(false, List.of("remote"), providers).appendTo(new ToolCallback[0])).isEmpty();
        assertThat(new McpToolRegistry(true, List.of(), providers).appendTo(new ToolCallback[0])).isEmpty();
        verifyNoInteractions(providers);
    }
    @Test void allowlistAndLocalNamesCannotBeOverriddenByRemoteTools() {
        var local = callback("local"); var provider = mock(ToolCallbackProvider.class);
        var discovered = new ToolCallback[]{callback("local"), callback("shell"), callback("allowed"), callback("denied"), callback("MemoryView"), callback("MemoryDelete")};
        when(provider.getToolCallbacks()).thenReturn(discovered);
        var registry = new McpToolRegistry(true, List.of("local", "shell", "allowed", "MemoryView", "MemoryDelete"), () -> List.of(provider));
        assertThat(registry.appendTo(new ToolCallback[]{local}, List.of("shell")))
                .extracting(c -> c.getToolDefinition().name()).containsExactly("local", "allowed");
        assertThat(registry.state()).isEqualTo(McpToolRegistry.State.AVAILABLE);
    }
    @Test void unavailableProviderDoesNotRemoveLocalTools() {
        var local = callback("local"); var provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenThrow(new IllegalStateException("secret endpoint"));
        var registry = new McpToolRegistry(true, List.of("remote"), () -> List.of(provider));
        assertThat(registry.appendTo(new ToolCallback[]{local})).containsExactly(local);
        assertThat(registry.state()).isEqualTo(McpToolRegistry.State.UNAVAILABLE);
    }
    @Test void contextOwnershipAndCancellationAreForwardedAndErrorsAreNotSuccessfulResults() {
        var remote = callback("remote"); var provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{remote});
        var wrapped = new McpToolRegistry(true, List.of("remote"), () -> List.of(provider)).appendTo(new ToolCallback[0])[0];
        var context = new ToolContext(Map.of("chat_memory_conversation_id", "fixture-owner"));
        when(remote.call("{}", context)).thenReturn("owned result");
        assertThat(wrapped.call("{}", context)).isEqualTo("owned result");
        verify(remote).call("{}", context);
        when(remote.call("{}", context)).thenThrow(new IllegalStateException("private endpoint token"));
        assertThat(wrapped.call("{}", context)).contains("failed").doesNotContain("private endpoint token");
        doThrow(new CancellationException("cancelled")).when(remote).call("{}", context);
        assertThatThrownBy(() -> wrapped.call("{}", context)).isInstanceOf(CancellationException.class);
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> wrapped.call("{}")).isInstanceOf(CancellationException.class); }
        finally { Thread.interrupted(); }
    }
}
