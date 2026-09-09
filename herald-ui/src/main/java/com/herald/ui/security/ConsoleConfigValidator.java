package com.herald.ui.security;

import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.*;

/** Resolve Spring configuration without creating a database, server or application beans. */
public final class ConsoleConfigValidator {
    private ConsoleConfigValidator() {}
    public static String warning(String address, boolean auth, boolean secureCookie) {
        boolean loopback = "127.0.0.1".equals(address) || "::1".equals(address) || "localhost".equalsIgnoreCase(address);
        if (!loopback && !auth) return "Console listens outside loopback without authentication. Bind 127.0.0.1 or configure HERALD_UI_AUTH_BEARER_TOKEN. See docs/remote-access.md.";
        if (!loopback && !secureCookie) return "Console session cookies are not Secure on a non-loopback listener. Use HTTPS and secure cookies. See docs/remote-access.md.";
        return null;
    }
    static void prepareEnvironment(ConfigurableEnvironment environment, String... args) {
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        // Startup processes JSON before config data; it may override bind/auth
        // settings or even supply spring.config.additional-location.
        new org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor()
                .postProcessEnvironment(environment, null);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
    }

    public static void main(String[] args) {
        try {
            var environment = new StandardEnvironment();
            prepareEnvironment(environment, args);
            String token = environment.getProperty("herald.ui.auth.bearer-token", "");
            boolean secure = environment.getProperty("herald.ui.auth.secure-cookie", Boolean.class, true);
            // Apply the same credential validation as startup, without revealing the value.
            var auth = new ConsoleAuth(token, secure);
            String warning = warning(environment.getProperty("server.address", "127.0.0.1"), auth.enabled(), secure);
            if (warning != null) { System.out.println("WARN: " + warning); System.exit(1); }
            System.out.println("Console exposure configuration OK; authentication " + (auth.enabled() ? "enabled." : "off for local-only use."));
        } catch (Exception ex) {
            System.err.println("Console configuration could not be validated. Check property syntax, token length and configuration file locations.");
            System.exit(2);
        }
    }
}
