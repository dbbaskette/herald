package com.herald.api;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes names only; no credentials, tool schemas or invocation surface. */
@RestController
class SkillCapabilitiesController {
    private final List<String> tools;
    SkillCapabilitiesController(@Qualifier("activeToolNames") List<String> tools) {
        this.tools = List.copyOf(tools);
    }
    @GetMapping("/api/skills/capabilities")
    Map<String, Object> capabilities() {
        return Map.of("status", "available", "tools", tools);
    }
}
