package com.herald.config;

/** Public capability description. Never include credentials or credential-bearing URLs. */
public record CapabilityStatus(String id, CapabilityState state, String message, String setupAction) {}
