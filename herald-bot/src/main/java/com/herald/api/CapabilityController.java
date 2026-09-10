package com.herald.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.herald.config.CapabilityState;
import com.herald.config.CapabilityStatus;
import com.herald.config.HeraldConfig;
import com.herald.config.IntegrationLifecycle;
import com.herald.config.ProviderCapabilities;

/**
 * Reports Herald's configured runtime surface without exposing credentials.
 * A {@code healthy} result means a local probe succeeded or the configuration
 * is usable; messages state explicitly when remote connectivity was not tested.
 */
@RestController
class CapabilityController {

    private static final String SETUP = "docs/provider-capabilities.md";

    private final Environment environment;
    private final HeraldConfig config;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final List<String> activeTools;

    CapabilityController(Environment environment, HeraldConfig config,
                         ObjectProvider<JdbcTemplate> jdbcProvider,
                         @Qualifier("activeToolNames") List<String> activeTools) {
        this.environment = environment;
        this.config = config;
        this.jdbcProvider = jdbcProvider;
        this.activeTools = List.copyOf(activeTools);
    }

    @GetMapping("/api/capabilities")
    Map<String, Object> capabilities() {
        var providers = ProviderCapabilities.resolve(environment);
        List<CapabilityStatus> capabilities = new ArrayList<>();
        capabilities.add(ProviderCapabilities.telegram(environment));

        CapabilityStatus persistence = persistence();
        capabilities.add(persistence);
        capabilities.add(dependent("memory", persistence, "Memory database is accessible."));
        capabilities.add(lifecycle("cron", IntegrationLifecycle.cronEnabled(environment), persistence,
                "Cron is enabled.", "Cron is disabled by configuration."));
        capabilities.add(lifecycle("meeting-notes", IntegrationLifecycle.meetingNotesEnabled(environment), persistence,
                "MeetingNotes ingestion is enabled.", "MeetingNotes integration is disabled."));
        capabilities.add(google());
        capabilities.add(obsidian());
        capabilities.add(mcp());
        capabilities.add(a2aClient());
        capabilities.add(enabled("a2a-server", "herald.a2a.server.enabled",
                "A2A server is enabled.", "A2A server is disabled."));

        Map<String, Object> providerSummary = new LinkedHashMap<>();
        providerSummary.put("requested", providers.requestedProvider());
        providerSummary.put("effective", providers.effectiveProvider());
        providerSummary.put("fallback", providers.fallback());
        providerSummary.put("states", providers.providers().stream().map(CapabilityController::publicStatus).toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providers", providerSummary);
        result.put("capabilities", capabilities.stream().map(CapabilityController::publicStatus).toList());
        result.put("tools", activeTools);
        return result;
    }

    private CapabilityStatus persistence() {
        if (IntegrationLifecycle.taskMode(environment)) {
            return status("persistence", CapabilityState.DISABLED, "Persistence is disabled in task-agent mode.");
        }
        if (!propertyEnabled("herald.persistence.enabled")) {
            return status("persistence", CapabilityState.DISABLED, "Persistence is disabled by configuration.");
        }
        if (!ProviderCapabilities.hasText(environment.getProperty("herald.memory.db-path"))) {
            return status("persistence", CapabilityState.UNCONFIGURED, "Set herald.memory.db-path to enable persistence.");
        }
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            return status("persistence", CapabilityState.UNAVAILABLE, "Persistence was enabled but its database bean is unavailable.");
        }
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return status("persistence", CapabilityState.HEALTHY, "Persistence database is accessible.");
        } catch (Exception failure) {
            return status("persistence", CapabilityState.FAILED, "Persistence database probe failed.");
        }
    }

    private CapabilityStatus dependent(String id, CapabilityStatus dependency, String healthyMessage) {
        if (dependency.state() != CapabilityState.HEALTHY) {
            return status(id, dependency.state(), dependency.message());
        }
        return status(id, CapabilityState.HEALTHY, healthyMessage);
    }

    private CapabilityStatus lifecycle(String id, boolean enabled, CapabilityStatus persistence,
                                       String healthyMessage, String disabledMessage) {
        if (!enabled) {
            return status(id, CapabilityState.DISABLED, disabledMessage);
        }
        if (persistence.state() != CapabilityState.HEALTHY) {
            return status(id, persistence.state(), persistence.message());
        }
        return status(id, CapabilityState.HEALTHY, healthyMessage);
    }

    private CapabilityStatus google() {
        if (!IntegrationLifecycle.googleEnabled(environment)) {
            return status("google-workspace", CapabilityState.DISABLED, "Google Workspace integration is disabled.");
        }
        boolean registered = activeTools.stream().anyMatch(name ->
                name.equals("gws") || name.equals("gmail_threads_list") || name.equals("calendar_events_list"));
        return registered
                ? status("google-workspace", CapabilityState.HEALTHY, "Google Workspace CLI is available and its tools are registered.")
                : status("google-workspace", CapabilityState.UNAVAILABLE, "Google Workspace CLI is not authenticated or unavailable.");
    }

    private CapabilityStatus obsidian() {
        if (!ProviderCapabilities.hasText(config.obsidianVaultPath())) {
            return status("obsidian", CapabilityState.DISABLED, "Obsidian vault integration is not configured.");
        }
        return status("obsidian", CapabilityState.HEALTHY, "Obsidian vault path is configured; filesystem access has not been probed.");
    }

    private CapabilityStatus mcp() {
        if (!propertyEnabled("spring.ai.mcp.client.enabled")) {
            return status("mcp-client", CapabilityState.DISABLED, "MCP client is disabled.");
        }
        return status("mcp-client", CapabilityState.UNKNOWN, "MCP client is enabled; connection telemetry is unavailable.");
    }

    private CapabilityStatus a2aClient() {
        if (!propertyEnabled("herald.a2a.client.enabled")) {
            return status("a2a-client", CapabilityState.DISABLED, "A2A client is disabled.");
        }
        if (config.a2aAgents().isEmpty()) {
            return status("a2a-client", CapabilityState.UNCONFIGURED, "A2A client is enabled but no remote agents are configured.");
        }
        return status("a2a-client", CapabilityState.HEALTHY, "A2A client agents are configured; remote availability has not been probed.");
    }

    private CapabilityStatus enabled(String id, String property, String enabledMessage, String disabledMessage) {
        return propertyEnabled(property)
                ? status(id, CapabilityState.HEALTHY, enabledMessage)
                : status(id, CapabilityState.DISABLED, disabledMessage);
    }

    private boolean propertyEnabled(String property) {
        return environment.getProperty(property, Boolean.class, false);
    }

    private static CapabilityStatus status(String id, CapabilityState state, String message) {
        return new CapabilityStatus(id, state, message, SETUP);
    }

    private static Map<String, String> publicStatus(CapabilityStatus status) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("id", status.id());
        result.put("state", status.state().name().toLowerCase(Locale.ROOT));
        result.put("message", status.message());
        result.put("setupAction", status.setupAction());
        return result;
    }
}
