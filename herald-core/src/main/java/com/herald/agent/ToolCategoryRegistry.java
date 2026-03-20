package com.herald.agent;

import com.herald.tools.FileSystemTools;
import com.herald.tools.WebTools;

import java.util.*;
import java.util.function.Supplier;

/**
 * Maps tool category names (from agents.md) to tool instances.
 * Supports both standalone and Spring-managed tool resolution.
 *
 * <p>In standalone mode (default constructor), only stateless tools that
 * herald-core can create without DI are registered (filesystem, web).
 * Other modules (persistence, telegram) add their tools by calling
 * {@link #register(String, Object)} at startup.</p>
 */
public class ToolCategoryRegistry {

    private final Map<String, Supplier<Object>> registry = new LinkedHashMap<>();

    public ToolCategoryRegistry() {
        // Register stateless tools that can be created without DI
        register("filesystem", FileSystemTools::new);
        register("web", () -> new WebTools(""));
    }

    public void register(String category, Supplier<Object> supplier) {
        registry.put(category, supplier);
    }

    public void register(String category, Object toolInstance) {
        registry.put(category, () -> toolInstance);
    }

    /**
     * Resolve a list of category names to tool instances.
     * "all" returns every registered tool.
     */
    public List<Object> resolve(List<String> categories) {
        if (categories == null || categories.isEmpty()) return List.of();

        Set<String> requested = new LinkedHashSet<>(categories);
        if (requested.contains("all")) {
            requested = registry.keySet();
        }

        List<Object> tools = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String cat : requested) {
            Supplier<Object> supplier = registry.get(cat);
            if (supplier != null) {
                tools.add(supplier.get());
            } else {
                missing.add(cat);
            }
        }

        if (!missing.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(ToolCategoryRegistry.class)
                    .warn("Tool categories not available (missing module?): {}", missing);
        }

        return tools;
    }

    public Set<String> availableCategories() {
        return Set.copyOf(registry.keySet());
    }
}
