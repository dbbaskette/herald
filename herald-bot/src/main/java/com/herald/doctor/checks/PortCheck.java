package com.herald.doctor.checks;

import java.io.IOException;
import java.net.ServerSocket;

import com.herald.doctor.HealthCheck;

/**
 * Probes a TCP port — free means Herald can bind, occupied means something
 * else will take the port (or Herald itself is already running — in which
 * case the human running doctor probably knows).
 */
public class PortCheck implements HealthCheck {

    private final String displayName;
    private final int port;

    public PortCheck(String displayName, int port) {
        this.displayName = displayName;
        this.port = port;
    }

    @Override
    public String name() {
        return displayName + " port " + port;
    }

    @Override
    public Result run() {
        try (ServerSocket ignored = new ServerSocket(port)) {
            return Result.ok("free");
        } catch (IOException e) {
            return Result.warn("in use — " + e.getMessage(),
                    "Herald may already be running, or another service owns the port. "
                            + "`lsof -i :" + port + "` to identify.");
        }
    }
}
