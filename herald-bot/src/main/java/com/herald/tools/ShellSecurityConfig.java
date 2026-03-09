package com.herald.tools;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "herald.security")
class ShellSecurityConfig {

    private List<String> blocklistPatterns = List.of(
            "rm\\s+-rf\\s+/",
            "mkfs",
            "dd\\s+if=",
            "sudo\\s+rm",
            "curl\\s+.*\\|\\s*sh",
            "wget\\s+.*\\|\\s*sh",
            "chmod\\s+-R\\s+777\\s+/",
            ":\\(\\)\\{\\s+:\\|:&\\s+\\};:",
            "mv\\s+/\\s",
            "rm\\s+-rf\\s+\\*",
            "> /dev/sda",
            "shutdown",
            "reboot",
            "init\\s+0",
            "halt"
    );

    private int shellTimeoutSeconds = 30;

    private int confirmationTimeoutSeconds = 60;

    public List<String> getBlocklistPatterns() {
        return blocklistPatterns;
    }

    public void setBlocklistPatterns(List<String> blocklistPatterns) {
        this.blocklistPatterns = blocklistPatterns;
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
