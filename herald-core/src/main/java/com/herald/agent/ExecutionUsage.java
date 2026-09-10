package com.herald.agent;

import java.util.List;

/** Completed provider usage remains available even when a later round fails or is cancelled. */
public record ExecutionUsage(List<ModelUsage> models) {
    public static final String OBSERVER = ExecutionUsage.class.getName() + ".observer";
    public record ModelUsage(String model, long input, long output, long cacheRead, long cacheWrite) {}
}
