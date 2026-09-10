package com.herald.agent;

/** Safe, user-visible stop reason without prompt/tool arguments or private reasoning. */
public final class ExecutionLimitException extends RuntimeException {
    public ExecutionLimitException(String reason) { super("Agent stopped: " + reason); }
}
