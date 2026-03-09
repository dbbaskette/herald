package com.herald.tools;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "herald.security")
class ShellSecurityConfig {

    /*
     * Best-effort blocklist — not a security boundary.
     * Known bypass vectors that are accepted limitations:
     *   - Full-path invocation (e.g. /sbin/shutdown, /sbin/mkfs.ext4) for commands
     *     not covered by path-prefixed variants below.
     *   - Variable indirection (e.g. CMD='rm -rf /'; $CMD).
     *   - Encoding / obfuscation tricks (e.g. base64-decoded commands).
     * The blocklist reduces accidental damage from AI-generated commands;
     * it is not a substitute for OS-level sandboxing.
     */
    private List<String> shellBlocklist = List.of(
            "rm\\s+-rf\\s+/",
            "\\bmkfs\\b",
            "dd\\s+if=",
            "sudo\\s+rm",
            "curl\\s+.*\\|\\s*sh",
            "wget\\s+.*\\|\\s*sh",
            "chmod\\s+-R\\s+777\\s+/",
            ":\\(\\)\\{\\s+:\\|:&\\s+\\};:",
            "mv\\s+/\\s",
            "rm\\s+-rf\\s+\\*",
            "> /dev/sda",
            "(?:^|[;&|]\\s*)(?:/(?:usr/)?s?bin/)?shutdown\\b",
            "(?:^|[;&|]\\s*)(?:/(?:usr/)?s?bin/)?reboot\\b",
            "init\\s+0",
            "(?:^|[;&|]\\s*)(?:/(?:usr/)?s?bin/)?halt\\b"
    );

    private int shellTimeoutSeconds = 30;

    private int confirmationTimeoutSeconds = 60;

    public List<String> getShellBlocklist() {
        return shellBlocklist;
    }

    public void setShellBlocklist(List<String> shellBlocklist) {
        this.shellBlocklist = shellBlocklist;
    }

    public int getShellTimeoutSeconds() {
        return shellTimeoutSeconds;
    }

    public void setShellTimeoutSeconds(int shellTimeoutSeconds) {
        this.shellTimeoutSeconds = shellTimeoutSeconds;
    }

    public int getConfirmationTimeoutSeconds() {
        return confirmationTimeoutSeconds;
    }

    public void setConfirmationTimeoutSeconds(int confirmationTimeoutSeconds) {
        this.confirmationTimeoutSeconds = confirmationTimeoutSeconds;
    }
}
