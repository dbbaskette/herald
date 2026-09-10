package com.herald.ui.security;

import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/** Public auth bootstrap only; application data remains under protected /api. */
@RestController
@RequestMapping("/auth/session")
class ConsoleSessionController {
    private static final Logger log = LoggerFactory.getLogger(ConsoleSessionController.class);
    private final ConsoleAuth auth;
    ConsoleSessionController(ConsoleAuth auth) { this.auth = auth; }
    @GetMapping Map<String, Object> status(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var session = auth.session(request);
        return Map.of("enabled", auth.enabled(), "authenticated", !auth.enabled() || session != null,
                "csrf", session == null ? "" : session.csrf());
    }
    @PostMapping Map<String, Object> login(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!auth.enabled()) return Map.of("enabled", false, "authenticated", true, "csrf", "");
        if (!auth.bearer(request)) { log.warn("Console sign-in rejected"); ConsoleAuthFilter.error(response, 401, "Invalid console token."); return null; }
        var session = auth.login(request, response);
        return Map.of("enabled", true, "authenticated", true, "csrf", session.csrf());
    }
    @DeleteMapping void logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var session = auth.session(request);
        if (auth.enabled() && !auth.bearer(request) && (session == null || !auth.csrf(request, session))) {
            ConsoleAuthFilter.error(response, 403, "Refresh your console session before retrying."); return;
        }
        auth.logout(request, response); response.setStatus(204);
    }
}
