package com.herald.ui.security;

import java.net.URI;
import java.time.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConsoleAuthTest {
    private static final String TOKEN = "fixture-console-token-32-characters-minimum";
    private MockMvc mvc;
    private MutableClock clock;
    @RestController static class ProtectedApi {
        @GetMapping("/api/chat/stream") String chat() { return "agent turn started"; }
        @RequestMapping("/api/probe") String probe() { return "private data"; }
        @GetMapping(value="/api/status/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE) String stream() { return "data: private\n\n"; }
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now;}
    }
    @BeforeEach void setup() { clock = new MutableClock(); configure(TOKEN); }
    void configure(String token) {
        var auth = new ConsoleAuth(token, true, clock);
        mvc = MockMvcBuilders.standaloneSetup(new ConsoleSessionController(auth), new ProtectedApi())
                .addFilters(new ConsoleAuthFilter(auth)).build();
    }
    @Test void allApiMethodsAndSseRequireAuthentication() throws Exception {
        for (String method : new String[]{"GET","POST","PUT","PATCH","DELETE","OPTIONS","HEAD"})
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/api/probe"))
                    .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/status/stream")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/status/stream").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(content().string("data: private\n\n"));
    }
    @Test void encodedAndMatrixPathsDoNotBypassFilter() throws Exception {
        for (String path : new String[]{"/%61pi/probe", "/api;v=1/probe", "/api/probe?token=" + TOKEN})
            mvc.perform(get(URI.create(path))).andExpect(status().isUnauthorized());
    }
    @Test void bearerWorksAndWrongHeaderCannotUseSessionFallback() throws Exception {
        mvc.perform(post("/api/probe").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
        mvc.perform(get("/api/probe").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        var session = login();
        mvc.perform(get("/api/probe").cookie(session.cookie()).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
    }
    record Login(Cookie cookie, String csrf) {}
    Login login() throws Exception {
        var response = mvc.perform(post("/auth/session").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse();
        String cookieHeader = response.getHeader("Set-Cookie");
        assertThat(cookieHeader).contains("HttpOnly", "Secure", "SameSite=Strict").doesNotContain(TOKEN);
        String id = cookieHeader.substring(cookieHeader.indexOf('=') + 1, cookieHeader.indexOf(';'));
        var body = JsonMapper.builder().build().readTree(response.getContentAsString());
        return new Login(new Cookie(ConsoleAuth.COOKIE, id), body.get("csrf").asText());
    }
    @Test void browserSessionSupportsReadsAndRequiresCsrfForMutations() throws Exception {
        var session = login();
        mvc.perform(get("/api/probe").cookie(session.cookie())).andExpect(status().isOk());
        mvc.perform(get("/api/status/stream").cookie(session.cookie())).andExpect(status().isOk());
        mvc.perform(put("/api/probe").cookie(session.cookie())).andExpect(status().isForbidden());
        mvc.perform(put("/api/probe").cookie(session.cookie()).header("X-Herald-CSRF", "wrong")).andExpect(status().isForbidden());
        mvc.perform(put("/api/probe").cookie(session.cookie()).header("X-Herald-CSRF", session.csrf())).andExpect(status().isOk());
        mvc.perform(delete("/auth/session").cookie(session.cookie())).andExpect(status().isForbidden());
        mvc.perform(delete("/auth/session").cookie(session.cookie()).header("X-Herald-CSRF", session.csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/probe").cookie(session.cookie())).andExpect(status().isUnauthorized());
    }
    @Test void chatSseRequiresSameOriginBrowserOrCsrfButBearerClientsStillWork() throws Exception {
        var session = login();
        for (String site : new String[]{"same-site", "cross-site", "none"}) {
            mvc.perform(get("/api/chat/stream").cookie(session.cookie()).header("Sec-Fetch-Site", site))
                    .andExpect(status().isForbidden());
            mvc.perform(head("/api/chat/stream").cookie(session.cookie()).header("Sec-Fetch-Site", site))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/chat/stream").cookie(session.cookie())).andExpect(status().isForbidden());
        mvc.perform(get("/api/chat/stream").cookie(session.cookie()).header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/chat/stream").cookie(session.cookie()).header("X-Herald-CSRF", session.csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/chat/stream").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    private org.springframework.core.env.StandardEnvironment isolatedEnvironment() {
        // Keep startup's named property-source anchors: Boot inserts JSON before
        // systemProperties, below commandLineArgs. MockEnvironment lacks that
        // anchor and intentionally causes the processor to insert JSON first.
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().replace("systemProperties",
                new org.springframework.core.env.MapPropertySource("systemProperties", java.util.Map.of()));
        environment.getPropertySources().replace("systemEnvironment",
                new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", java.util.Map.of()));
        return environment;
    }

    @Test void validatorAppliesJsonOverridesAndCommandLinePrecedence() {
        var environment = isolatedEnvironment();
        environment.getPropertySources().addLast(new org.springframework.core.env.SystemEnvironmentPropertySource(
                "fixtureEnv", java.util.Map.of("SPRING_APPLICATION_JSON", "{\"server\":{\"address\":\"0.0.0.0\"}}")));
        ConsoleConfigValidator.prepareEnvironment(environment);
        assertThat(environment.getProperty("server.address")).isEqualTo("0.0.0.0");
        assertThat(ConsoleConfigValidator.warning(environment.getProperty("server.address"), false, true))
                .contains("without authentication");
        var cli = isolatedEnvironment();
        ConsoleConfigValidator.prepareEnvironment(cli,
                "--spring.application.json={\"server\":{\"address\":\"0.0.0.0\"}}", "--server.address=127.0.0.1");
        assertThat(cli.getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test void expiredSessionAndRotatedServerTokenInvalidateCookie() throws Exception {
        var session = login();
        clock.now = clock.now.plus(Duration.ofHours(8));
        mvc.perform(get("/api/probe").cookie(session.cookie())).andExpect(status().isUnauthorized());
        configure(TOKEN + "-rotated");
        mvc.perform(get("/api/probe").cookie(session.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/probe").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isUnauthorized());
    }
    @Test void unsetTokenRetainsLocalBehaviorAndShortTokensFailClosed() throws Exception {
        configure(""); mvc.perform(post("/api/probe")).andExpect(status().isOk());
        mvc.perform(get("/auth/session")).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.authenticated").value(true));
        assertThatThrownBy(() -> new ConsoleAuth("short", true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void exposureChecksFlagIpv4Ipv6AndLanWithoutPrintingToken() {
        for (String address : new String[]{"0.0.0.0","::","192.168.1.20"})
            assertThat(ConsoleConfigValidator.warning(address, false, true)).contains("without authentication");
        assertThat(ConsoleConfigValidator.warning("127.0.0.1", false, true)).isNull();
        assertThat(ConsoleConfigValidator.warning("0.0.0.0", true, false)).contains("not Secure");
    }
}
