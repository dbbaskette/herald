package com.herald.tools;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GwsToolConfiguration {
    /** A failed/disabled CLI probe exposes no annotated Google tool bean. */
    @Bean GwsTools gwsTools(GwsAvailabilityChecker checker) {
        return checker.isAvailable() ? new GwsTools(checker) : null;
    }
}
