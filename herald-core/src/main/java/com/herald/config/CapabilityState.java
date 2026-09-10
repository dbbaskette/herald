package com.herald.config;

/** Configuration and observed lifecycle states; configuration alone does not prove remote health. */
public enum CapabilityState { HEALTHY, DISABLED, UNCONFIGURED, UNAVAILABLE, FAILED, UNKNOWN }
