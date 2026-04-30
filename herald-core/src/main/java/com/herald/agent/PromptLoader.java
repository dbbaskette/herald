package com.herald.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Loads a bundled system prompt with user-override support.
 *
 * <p>For any prompt at {@code classpath:prompts/<NAME>.md}, the loader first
 * checks {@code ~/.herald/prompts/<NAME>.md} (resolved via the
 * {@code HERALD_HOME} env var, defaulting to {@code ~/.herald}) and uses it
 * if present. This lets the user edit prompts via the web UI's Prompts page
 * without having to rebuild Herald — the override file simply replaces the
 * bundled one at load time.</p>
 *
 * <p>Symbolic, simple, and intentionally narrow: prompts are read once at
 * startup so changes take effect on the next restart. Hot-reload is out of
 * scope.</p>
 */
public final class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    private PromptLoader() {}

    /**
     * Resolve the directory holding user prompt overrides.
     * Honors the {@code HERALD_HOME} env var, falling back to
     * {@code ~/.herald/prompts}.
     */
    public static Path overrideDir() {
        String heraldHome = System.getenv("HERALD_HOME");
        Path home = (heraldHome != null && !heraldHome.isBlank())
                ? Paths.get(heraldHome)
                : Paths.get(System.getProperty("user.home"), ".herald");
        return home.resolve("prompts");
    }

    /**
     * Load a prompt by its bundled classpath path (e.g.
     * {@code "prompts/MAIN_AGENT_SYSTEM_PROMPT.md"}). If a same-named file
     * exists under {@link #overrideDir()}, that content is returned instead.
     */
    public static String load(String classpathLocation) {
        String basename = basename(classpathLocation);
        Path override = overrideDir().resolve(basename);
        if (Files.isRegularFile(override) && Files.isReadable(override)) {
            try {
                String content = Files.readString(override, StandardCharsets.UTF_8);
                log.info("Loaded prompt override from {}", override);
                return content;
            } catch (IOException e) {
                log.warn("Failed to read prompt override at {} — falling back to classpath: {}",
                        override, e.getMessage());
            }
        }
        return loadClasspath(classpathLocation);
    }

    /** Load a prompt directly from a Spring {@link Resource}, with override support based on its filename. */
    public static String load(Resource resource) {
        String filename = resource.getFilename();
        if (filename != null) {
            Path override = overrideDir().resolve(filename);
            if (Files.isRegularFile(override) && Files.isReadable(override)) {
                try {
                    String content = Files.readString(override, StandardCharsets.UTF_8);
                    log.info("Loaded prompt override from {}", override);
                    return content;
                } catch (IOException e) {
                    log.warn("Failed to read prompt override at {} — falling back to classpath: {}",
                            override, e.getMessage());
                }
            }
        }
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load prompt resource " + resource, e);
        }
    }

    private static String loadClasspath(String classpathLocation) {
        String location = classpathLocation.startsWith("/") ? classpathLocation.substring(1) : classpathLocation;
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load prompt: " + classpathLocation, e);
        }
    }

    private static String basename(String classpathLocation) {
        int slash = classpathLocation.lastIndexOf('/');
        return slash >= 0 ? classpathLocation.substring(slash + 1) : classpathLocation;
    }
}
