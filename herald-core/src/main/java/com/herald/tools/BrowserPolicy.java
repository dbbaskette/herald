package com.herald.tools;

import java.net.*;
import java.util.*;

/** Exact HTTP(S) origin policy; never accepts wildcard hosts or implicit local networks. */
final class BrowserPolicy {
    private final Set<String> origins;
    private final boolean fixtureLoopback;
    BrowserPolicy(String configured, boolean fixtureLoopback) {
        this.fixtureLoopback=fixtureLoopback;
        Set<String> parsed=new HashSet<>();
        for (String value:configured.split(",")) if (!value.isBlank()) parsed.add(origin(URI.create(value.trim())));
        if (parsed.isEmpty()) throw new IllegalArgumentException("Browser requires explicit allowed-origins");
        origins=Set.copyOf(parsed);
    }
    void check(String url) {
        URI uri=URI.create(url);
        if (!origins.contains(origin(uri))) throw new SecurityException("Browser origin is not allowed");
        try {
            for (InetAddress address:InetAddress.getAllByName(uri.getHost())) {
                if (fixtureLoopback && address.isLoopbackAddress()) continue;
                byte[] raw=address.getAddress();
                boolean uniqueLocal=raw.length==16 && (raw[0]&0xfe)==0xfc;
                int first=raw[0]&255, second=raw[1]&255;
                boolean reservedV4=raw.length==4 && (first==0 || first>=224
                        || (first==100 && second>=64 && second<=127)
                        || (first==198 && (second==18 || second==19)));
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocal || reservedV4)
                    throw new SecurityException("Private browser destinations are blocked");
            }
        } catch (UnknownHostException e) { throw new SecurityException("Browser host could not be resolved"); }
    }
    static String origin(URI uri) {
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost()==null || uri.getRawUserInfo()!=null) throw new SecurityException("Expected an HTTP(S) origin without credentials");
        String scheme=uri.getScheme().toLowerCase(Locale.ROOT);
        int port=uri.getPort()<0 ? (scheme.equals("https")?443:80):uri.getPort();
        return scheme+"://"+uri.getHost().toLowerCase(Locale.ROOT)+":"+port;
    }
}
