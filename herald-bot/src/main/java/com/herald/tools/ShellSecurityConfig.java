package com.herald.tools;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "herald.security")
class ShellSecurityConfig {

    private List<String> shellBlocklist = List.of(
            "rm\\s+-rf\\s+/",
            "mkfs",
            "dd\\s+if=",
            "sudo\\s+rm",
            "curl\\s+.*\\|\\s*sh",
            "wget\\s+.*\\|\\s*sh",
            "chmod\\s+-R\\s+777\\s+/",
            ":(){ :\\|:& };:",
            "mv\\s+/\\s",
            "rm\\s+-rf\\s+\\*",
            "> /dev/sda",
            "shutdown",
            "reboot",
            "init\\s+0",
            "halt"
    );

    public List<String> getShellBlocklist() {
        return shellBlocklist;
    }

    public void setShellBlocklist(List<String> shellBlocklist) {
        this.shellBlocklist = shellBlocklist;
    }

    public List<String> getBlocklistPatterns() {
        return shellBlocklist;
    }
}
