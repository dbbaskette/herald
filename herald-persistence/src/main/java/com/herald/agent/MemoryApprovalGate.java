package com.herald.agent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gates memory mutations on per-type approval. When the resolved
 * {@link MemoryApprovalPolicy.Mode} for a tool call is {@code CONFIRM_DIFF},
 * we render the edit as a unified-diff-style preview, send it to Telegram
 * via {@link MessageSenderLike}, and block the calling tool until the user
 * responds with {@code /confirm <id> yes|no}, the timeout elapses, or the
 * user explicitly declines.
 *
 * <p>This is a near-clone of {@code com.herald.agent.ApprovalGate} (shell
 * commands) but lives in herald-persistence so it can sit alongside
 * {@link LoggingMemoryToolCallback} without creating a reverse dep. See
 * issue #317 for the design.</p>
 */
public class MemoryApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(MemoryApprovalGate.class);

    /**
     * Minimal seam onto Telegram so this class doesn't depend on
     * {@code MessageSender} (herald-core). The bot wires
     * {@code MessageSender::sendMessage} as a method reference.
     */
    @FunctionalInterface
    public interface MessageSenderLike {
        void sendMessage(String text);
    }

    public enum Decision { APPLY, DISCARD, TIMEOUT }

    private final MessageSenderLike messageSender;
    private final MemoryApprovalPolicy policy;
    private final Path memoriesRoot;
    private final Path logFile;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    public MemoryApprovalGate(MessageSenderLike messageSender,
                              MemoryApprovalPolicy policy,
                              Path memoriesRoot,
                              Path logFile) {
        this.messageSender = messageSender;
        this.policy = policy == null ? MemoryApprovalPolicy.disabled() : policy;
        this.memoriesRoot = memoriesRoot;
        this.logFile = logFile;
    }

    /**
     * Evaluate a pending tool call against the policy.
     *
     * @return {@link Decision#APPLY} if the call should proceed (auto-mode or
     *         user approved); {@link Decision#DISCARD} if the user declined;
     *         {@link Decision#TIMEOUT} if no response arrived within the
     *         configured window. The caller (typically
     *         {@link LoggingMemoryToolCallback}) translates non-APPLY into
     *         a rejection string handed back to the agent.
     */
    public Decision evaluate(String toolName, String toolInput) {
        String relativePath = LoggingMemoryToolCallback.extractPath(toolInput);
        String createContent = "memorycreate".equalsIgnoreCase(toolName)
                ? MemoryDiffRenderer.extract(toolInput, "content") : null;
        String pageType = MemoryDiffRenderer.resolvePageType(memoriesRoot, relativePath, createContent);
        MemoryApprovalPolicy.Mode mode = policy.resolveMode(toolName, pageType);

        if (mode == MemoryApprovalPolicy.Mode.AUTO) {
            return Decision.APPLY;
        }

        // CONFIRM_DIFF — but if there's no MessageSender (task-agent / no
        // Telegram), we can't ask the user. Default to apply rather than
        // silently block the agent loop.
        if (messageSender == null) {
            log.debug("CONFIRM_DIFF requested for {} but no MessageSender — falling back to APPLY",
                    toolName);
            return Decision.APPLY;
        }

        String approvalId = UUID.randomUUID().toString().substring(0, 8);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(approvalId, future);

        String diff = MemoryDiffRenderer.render(toolName, toolInput, memoriesRoot);
        try {
            messageSender.sendMessage("Memory edit needs approval:\n\n"
                    + diff + "\n\n"
                    + "Reply: /confirm " + approvalId + " yes  OR  /confirm " + approvalId + " no");

            Boolean approved = future.get(policy.timeoutSeconds(), TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(approved)) {
                appendLogEvent("APPROVED", relativePath, approvalId);
                log.info("Memory edit approved (id={}, path={})", approvalId, relativePath);
                return Decision.APPLY;
            }
            appendLogEvent("DISCARDED", relativePath, approvalId);
            log.info("Memory edit discarded (id={}, path={})", approvalId, relativePath);
            return Decision.DISCARD;
        } catch (TimeoutException e) {
            appendLogEvent("DISCARDED", relativePath, approvalId, "reason", "timeout");
            log.warn("Memory edit timed out after {}s (id={}, path={})",
                    policy.timeoutSeconds(), approvalId, relativePath);
            return Decision.TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.DISCARD;
        } catch (Exception e) {
            log.error("Memory approval gate error for id={}: {}", approvalId, e.getMessage());
            return Decision.DISCARD;
        } finally {
            pending.remove(approvalId);
        }
    }

    /**
     * Resolve a pending approval. Wired from the {@code /confirm <id> yes|no}
     * Telegram command handler. Same shape as {@code ApprovalGate.resolve}.
     */
    public boolean resolve(String approvalId, boolean approved) {
        CompletableFuture<Boolean> future = pending.get(approvalId);
        if (future == null) return false;
        future.complete(approved);
        return true;
    }

    private void appendLogEvent(String event, String path, String approvalId, String... extras) {
        if (logFile == null) return;
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            if (path != null) fields.put("path", path);
            fields.put("approval_id", approvalId);
            for (int i = 0; i + 1 < extras.length; i += 2) {
                fields.put(extras[i], extras[i + 1]);
            }
            MemoryLogWriter.appendEvent(logFile, event, fields);
        } catch (Exception e) {
            log.debug("Failed to append memory approval log: {}", e.getMessage());
        }
    }
}
