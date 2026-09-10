package com.herald.config;

import java.util.List;

public record ProviderResolution(String requestedProvider, String effectiveProvider,
                                 boolean fallback, List<CapabilityStatus> providers) {
    public ProviderResolution { providers = List.copyOf(providers); }
    public boolean usable() { return effectiveProvider != null; }
}
