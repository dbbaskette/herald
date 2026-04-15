package com.herald.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the shared task-management / tool-use guidance snippet from the
 * classpath. Injected into both the Ultron persistent prompt (via the
 * {@code {task_management_guidance}} placeholder) and ephemeral agents.md
 * system prompts (via {@link AgentFactory}, opt-out per profile).
 *
 * <p>Adapted from {@code MAIN_AGENT_SYSTEM_PROMPT_V2.md} in
 * spring-ai-agent-utils (Part 3 of the Spring AI Agentic Patterns series).</p>
 */
public final class TaskManagementGuidance {

    private static final String RESOURCE_PATH = "/prompts/TASK_MANAGEMENT_GUIDANCE.md";

    private static volatile String cached;

    private TaskManagementGuidance() {}

    /**
     * Returns the guidance snippet, loaded lazily and cached for the JVM lifetime.
     * The snippet is a static classpath resource; cache-busting is not needed.
     */
    public static String load() {
        String local = cached;
        if (local != null) {
            return local;
        }
        synchronized (TaskManagementGuidance.class) {
            if (cached == null) {
                cached = readResource();
            }
            return cached;
        }
    }

    private static String readResource() {
        try (InputStream in = TaskManagementGuidance.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Classpath resource " + RESOURCE_PATH + " not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + RESOURCE_PATH, e);
        }
    }
}
