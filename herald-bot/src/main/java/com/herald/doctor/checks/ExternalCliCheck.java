package com.herald.doctor.checks;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.herald.doctor.HealthCheck;

/**
 * Checks that an external CLI is on {@code PATH}. Optional — a missing CLI
 * degrades one skill, not Herald itself — so severity defaults to WARN.
 * Never FAIL unless you know the CLI is load-bearing for a default path.
 *
 * <p>Version reporting is best-effort; if {@code --version} returns non-zero
 * but the command is on PATH, we still report ok with "version unknown".</p>
 */
public class ExternalCliCheck implements HealthCheck {

    private final String displayName;
    private final String command;
    private final String[] versionArgs;
    private final String fixHint;
    private final boolean required;

    public ExternalCliCheck(String displayName, String command, String fixHint) {
        this(displayName, command, new String[]{"--version"}, fixHint, false);
    }

    public ExternalCliCheck(String displayName, String command, String[] versionArgs,
                            String fixHint, boolean required) {
        this.displayName = displayName;
        this.command = command;
        this.versionArgs = versionArgs == null ? new String[0] : versionArgs;
        this.fixHint = fixHint;
        this.required = required;
    }

    @Override
    public String name() {
        return displayName;
    }

    @Override
    public Result run() {
        if (!isOnPath(command)) {
            return required
                    ? Result.fail("not installed (or not on PATH)", fixHint)
                    : Result.warn("not installed (optional)", fixHint);
        }
        String version = bestEffortVersion();
        if (version == null) {
            return Result.ok(command + " on PATH (version unknown)");
        }
        // Avoid "gws gws 0.16.0" when the --version output already leads with the command name.
        String msg = version.toLowerCase().startsWith(command.toLowerCase() + " ")
                ? version : command + " " + version;
        return Result.ok(msg);
    }

    private boolean isOnPath(String cmd) {
        try {
            Process p = new ProcessBuilder("/bin/sh", "-c", "command -v " + cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private String bestEffortVersion() {
        try {
            String[] cmdArr = new String[versionArgs.length + 1];
            cmdArr[0] = command;
            System.arraycopy(versionArgs, 0, cmdArr, 1, versionArgs.length);
            Process p = new ProcessBuilder(cmdArr).redirectErrorStream(true).start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            String output = new String(p.getInputStream().readAllBytes()).trim();
            if (output.isEmpty()) return null;
            // First line is usually "tool vX.Y.Z" or "X.Y.Z".
            String firstLine = output.split("\n", 2)[0].trim();
            return firstLine.length() > 80 ? firstLine.substring(0, 77) + "..." : firstLine;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
