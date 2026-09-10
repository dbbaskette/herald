package com.herald.doctor.checks;
import java.util.Map;
import com.herald.doctor.HealthCheck;
/** Checks launcher/environment overrides; UI config validate resolves custom Spring files. */
public final class NetworkExposureCheck implements HealthCheck {
    private final Map<String,String> env;
    public NetworkExposureCheck(Map<String,String> env) { this.env = Map.copyOf(env); }
    @Override public String name() { return "Network exposure"; }
    @Override public Result run() {
        String common = env.get("SERVER_ADDRESS");
        String ui = common != null ? common : env.getOrDefault("HERALD_UI_BIND_ADDRESS", "127.0.0.1");
        String bot = common != null ? common : env.getOrDefault("HERALD_BOT_BIND_ADDRESS", "127.0.0.1");
        boolean auth = !env.getOrDefault("HERALD_UI_AUTH_BEARER_TOKEN", "").isBlank();
        if (!loopback(ui) && !auth) return Result.warn("Console bind override exposes unauthenticated APIs.", "Use loopback/Tailscale Serve or set HERALD_UI_AUTH_BEARER_TOKEN. Run ./run.sh config validate for resolved UI config.");
        if (!loopback(bot)) return Result.warn("Bot bind override exposes internal APIs; console authentication does not protect them.", "Keep HERALD_BOT_BIND_ADDRESS=127.0.0.1. See docs/security-checklist.md.");
        return Result.ok("Loopback defaults / declared authenticated console override. Validate custom UI Spring files with ./run.sh config validate.");
    }
    private static boolean loopback(String value) { return "127.0.0.1".equals(value) || "::1".equals(value) || "localhost".equalsIgnoreCase(value); }
}
