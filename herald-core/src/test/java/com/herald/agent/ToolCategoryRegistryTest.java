package com.herald.agent;

import com.herald.tools.FileSystemTools;
import com.herald.tools.WebTools;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCategoryRegistryTest {

    @Test
    void defaultRegistryIncludesFilesystemAndWeb() {
        var registry = new ToolCategoryRegistry();
        assertThat(registry.availableCategories()).contains("filesystem", "web");
    }

    @Test
    void resolvesRegisteredCategories() {
        var registry = new ToolCategoryRegistry();
        List<Object> tools = registry.resolve(List.of("filesystem", "web"));
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0)).isInstanceOf(FileSystemTools.class);
        assertThat(tools.get(1)).isInstanceOf(WebTools.class);
    }

    @Test
    void resolveAllReturnsEverything() {
        var registry = new ToolCategoryRegistry();
        List<Object> tools = registry.resolve(List.of("all"));
        assertThat(tools).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void resolveMissingCategoryReturnsPartialList() {
        var registry = new ToolCategoryRegistry();
        List<Object> tools = registry.resolve(List.of("filesystem", "memory"));
        // "memory" not registered in standalone mode
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).isInstanceOf(FileSystemTools.class);
    }

    @Test
    void customRegistrationWorks() {
        var registry = new ToolCategoryRegistry();
        var customTool = new Object();
        registry.register("custom", customTool);
        List<Object> tools = registry.resolve(List.of("custom"));
        assertThat(tools).containsExactly(customTool);
    }

    @Test
    void emptyListReturnsEmpty() {
        var registry = new ToolCategoryRegistry();
        assertThat(registry.resolve(List.of())).isEmpty();
    }

    @Test
    void nullListReturnsEmpty() {
        var registry = new ToolCategoryRegistry();
        assertThat(registry.resolve(null)).isEmpty();
    }
}
