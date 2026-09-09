package com.herald.ui.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Single-operator console access. Secrets never appear in query parameters or logs. */
@Component
public class ConsoleAuth {
    static final String COOKIE = "herald_console_session";
    private final byte[] tokenHash;
    private final boolean enabled, secureCookie;
    private final Clock clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private static final Duration LIFETIME = Duration.ofHours(8);
    record Session(String csrf, Instant expires) {}
    @org.springframework.beans.factory.annotation.Autowired
    public ConsoleAuth(@Value("${herald.ui.auth.bearer-token:}") String token,
            @Value("${herald.ui.auth.secure-cookie:true}") boolean secureCookie) {
        this(token, secureCookie, Clock.systemUTC());
    }
    ConsoleAuth(String token, boolean secureCookie, Clock clock) {
        this.enabled = token != null && !token.isBlank();
        if (enabled && (token.length() < 32 || token.length() > 4096 || token.chars().anyMatch(Character::isWhitespace)))
            throw new IllegalArgumentException("Console bearer token must contain 32–4096 non-whitespace characters.");
        this.tokenHash = digest(enabled ? token : ""); this.secureCookie = secureCookie; this.clock = clock;
    }
    public boolean enabled() { return enabled; }
    boolean bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return enabled && header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && header.length() <= 4103 && MessageDigest.isEqual(tokenHash, digest(header.substring(7)));
    }
    Session session(HttpServletRequest request) {
        String id = cookie(request);
        Session session = id == null ? null : sessions.get(id);
        if (session != null && !session.expires().isAfter(clock.instant())) { sessions.remove(id); return null; }
        return session;
    }
    boolean csrf(HttpServletRequest request, Session session) {
        String supplied = request.getHeader("X-Herald-CSRF");
        return supplied != null && supplied.length() <= 128
                && MessageDigest.isEqual(digest(supplied), digest(session.csrf()));
    }
    synchronized Session login(HttpServletRequest request, HttpServletResponse response) {
        sessions.entrySet().removeIf(entry -> !entry.getValue().expires().isAfter(clock.instant()));
        if (sessions.size() >= 1000) throw new IllegalStateException("Too many console sessions; retry later.");
        String previous = cookie(request); if (previous != null) sessions.remove(previous);
        String id = secret(); Session session = new Session(secret(), clock.instant().plus(LIFETIME));
        sessions.put(id, session); writeCookie(response, id, LIFETIME); return session;
    }
    void logout(HttpServletRequest request, HttpServletResponse response) {
        String id = cookie(request); if (id != null) sessions.remove(id);
        writeCookie(response, "", Duration.ZERO);
    }
    private String secret() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private void writeCookie(HttpServletResponse response, String value, Duration age) {
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, value).httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path("/").maxAge(age).build().toString());
    }
    private String cookie(HttpServletRequest request) {
        if (request.getCookies() != null) for (var cookie : request.getCookies())
            if (COOKIE.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
    private static byte[] digest(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
