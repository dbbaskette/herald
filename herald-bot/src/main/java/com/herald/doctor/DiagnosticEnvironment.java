package com.herald.doctor;

import java.util.Map;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.*;

/** Resolve the same property files, JSON and command-line precedence without creating beans. */
public final class DiagnosticEnvironment {
    private DiagnosticEnvironment() {}
    public static ConfigurableEnvironment load(String[] args, Map<String,String> env) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new java.util.HashMap<>(env)));
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        new org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }
}
