package com.herald.tools;

/**
 * Functional interface for shell command execution.
 * When spring-ai-agent-utils ShellTools is available, register a bean
 * that delegates to it. Until then, HeraldShellDecorator provides
 * a built-in ProcessBuilder-based fallback.
 */
@FunctionalInterface
public interface ShellCommandExecutor {
    String execute(String command);
}
