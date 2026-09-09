package com.herald.ui.security;

import java.io.IOException;
import java.util.Set;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ConsoleAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ConsoleAuthFilter.class);
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private final ConsoleAuth auth;
    public ConsoleAuthFilter(ConsoleAuth auth) { this.auth = auth; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
        String path = org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (!(path.equals("/api") || path.startsWith("/api/") || path.startsWith("/api;")) || !auth.enabled()) {
            chain.doFilter(request, response); return;
        }
        response.setHeader("Cache-Control", "no-store");
        if (auth.bearer(request)) { chain.doFilter(request, response); return; }
        // An explicit incorrect Authorization header must not silently fall back to a cookie.
        var session = request.getHeader("Authorization") == null ? auth.session(request) : null;
        if (session == null) {
            log.warn("Console API authentication rejected");
            response.setHeader("WWW-Authenticate", "Bearer realm=\"Herald console\"");
            error(response, 401, "Console sign-in required."); return;
        }
        // This legacy SSE GET starts an agent turn and can run tools. SameSite
        // cookies alone do not isolate hostile sibling origins (including other
        // localhost ports). EventSource cannot set a CSRF header, so accept only
        // browser-proven same-origin requests, or an explicit valid CSRF nonce.
        boolean startsChat = ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && (path.equals("/api/chat/stream") || path.equals("/api/chat/stream/"));
        boolean sameOriginStream = startsChat && "same-origin".equals(request.getHeader("Sec-Fetch-Site"));
        if ((!SAFE.contains(request.getMethod()) || (startsChat && !sameOriginStream)) && !auth.csrf(request, session)) {
            log.warn("Console API CSRF validation rejected");
            error(response, 403, "Refresh your console session before retrying."); return;
        }
        chain.doFilter(request, response);
    }
    static void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
