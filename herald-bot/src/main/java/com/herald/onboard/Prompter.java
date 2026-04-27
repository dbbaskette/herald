package com.herald.onboard;

/**
 * Abstracts the user-facing prompts so the wizard can be unit-tested with
 * scripted answers and so non-interactive runs can pass {@code --answer=...}
 * flags through the same code path that interactive runs use.
 */
public interface Prompter {

    /** Read a single line of plain text. Returns "" if the user pressed Enter. */
    String prompt(String message);

    /**
     * Read a secret. Implementations may suppress echo; the test impl just
     * returns the next scripted answer.
     */
    String promptSecret(String message);

    /** Read a yes/no answer. Empty input returns {@code defaultYes}. */
    boolean confirm(String message, boolean defaultYes);

    /**
     * Block until the user presses Enter. Used to wait for the user to send
     * a Telegram message before we poll for the chat ID.
     */
    void waitForEnter(String message);

    /** Print a status line to the user. */
    void println(String line);
}
