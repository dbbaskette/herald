package com.herald.agent;

import java.nio.file.Path;
import java.time.Instant;
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
 * we render the edit as a unified-diff-style preview and block the calling
 * tool until the user responds — via Telegram {@code /confirm}, the Console
 * approval inbox, or timeout.
 *
 * <p>Web chat turns ({@link ChatChannelContext#isWeb()}) route prompts to the
 * Console through {@link PendingApprovalRegistry} and an optional
 * {@link ApprovalPromptHandler}; Telegram turns use {@link MessageSenderLike}.</p>
 */
public class MemoryApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(MemoryApprovalGate.class);

    @FunctionalInterface
    public interface MessageSenderLike {
        void sendMessage(String text);
    }

    public enum Decision { APPLY, DISCARD, TIMEOUT }

    private final MessageSenderLike messageSender;
    private final MemoryApprovalPolicy policy;
    private final Path memoriesRoot;
    private final Path logFile;
    private final PendingApprovalRegistry registry;
    private final ApprovalPromptHandler webPromptHandler;
    /** Legacy in-process map used when no registry is wired (unit tests). */
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> legacyPending = new ConcurrentHashMap<>();

    public MemoryApprovalGate(MessageSenderLike messageSender,
                              MemoryApprovalPolicy policy,
                              Path memoriesRoot,
                              Path logFile) {
        this(messageSender, policy, memoriesRoot, logFile, null, null);
    }

    public MemoryApprovalGate(MessageSenderLike messageSender,
                              MemoryApprovalPolicy policy,
                              Path memoriesRoot,
                              Path logFile,
                              PendingApprovalRegistry registry,
                              ApprovalPromptHandler webPromptHandler) {
        this.messageSender = messageSender;
        this.policy = policy == null ? MemoryApprovalPolicy.disabled() : policy;
        this.memoriesRoot = memoriesRoot;
        this.logFile = logFile;
        this.registry = registry;
        this.webPromptHandler = webPromptHandler;
    }

    public Decision evaluate(String toolName, String toolInput) {
        String relativePath = LoggingMemoryToolCallback.extractPath(toolInput);
        String createContent = "memorycreate".equalsIgnoreCase(toolName)
                ? MemoryDiffRenderer.extract(toolInput, "content") : null;
        String pageType = MemoryDiffRenderer.resolvePageType(memoriesRoot, relativePath, createContent);
        MemoryApprovalPolicy.Mode mode = policy.resolveMode(toolName, pageType);

        if (mode == MemoryApprovalPolicy.Mode.AUTO) {
            return Decision.APPLY;
        }

        // Unattended, machine-initiated turns (webhook ingest, cron, backfill) have
        // no human to answer an approval prompt — prompting would just time out and
        // discard the write. The user opted into these flows by configuring them, so
        // apply directly regardless of page type.
        if (ChatChannelContext.get() == ChatChannelContext.Channel.SYSTEM) {
            log.debug("System channel — auto-applying memory edit for {} ({})", toolName,
                    LoggingMemoryToolCallback.extractPath(toolInput));
            return Decision.APPLY;
        }

        boolean webChannel = ChatChannelContext.isWeb();
        if (!webChannel && messageSender == null) {
            log.debug("CONFIRM_DIFF requested for {} but no MessageSender — falling back to APPLY",
                    toolName);
            return Decision.APPLY;
        }

        String approvalId = UUID.randomUUID().toString().substring(0, 8);
        String diff = MemoryDiffRenderer.render(toolName, toolInput, memoriesRoot);
        CompletableFuture<Boolean> future = registerPending(
                approvalId, toolName, relativePath, diff, webChannel);

        try {
            if (webChannel) {
                log.info("Memory edit awaiting Console approval (id={}, path={})", approvalId, relativePath);
            } else {
                messageSender.sendMessage("Memory edit needs approval:\n\n"
                        + diff + "\n\n"
                        + "Reply: /confirm " + approvalId + " yes  OR  /confirm " + approvalId + " no");
            }

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
            deregisterPending(approvalId);
        }
    }

    private CompletableFuture<Boolean> registerPending(
            String approvalId, String toolName, String relativePath, String diff, boolean webChannel) {
        if (registry != null) {
            String conversationId = ChatChannelContext.getConversationId();
            String channel = webChannel ? "web" : "telegram";
            PendingApproval approval = new PendingApproval(
                    approvalId, "memory", conversationId, channel,
                    toolName, relativePath, diff, Instant.now(), policy.timeoutSeconds());
            CompletableFuture<Boolean> future = registry.register(approval);
            if (webChannel && webPromptHandler != null) {
                webPromptHandler.onApprovalRequired(approval);
            }
            return future;
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        legacyPending.put(approvalId, future);
        return future;
    }

    private void deregisterPending(String approvalId) {
        if (registry != null) {
            registry.remove(approvalId);
        } else {
            legacyPending.remove(approvalId);
        }
    }

    /**
     * Resolve a pending approval. Wired from Telegram {@code /confirm} and the
     * Console {@code POST /api/approvals/{id}/resolve} endpoint.
     */
    public boolean resolve(String approvalId, boolean approved) {
        if (registry != null) {
            return registry.resolve(approvalId, approved);
        }
        CompletableFuture<Boolean> future = legacyPending.get(approvalId);
        if (future == null) {
            return false;
        }
        future.complete(approved);
        return true;
    }

    private void appendLogEvent(String event, String path, String approvalId, String... extras) {
        if (logFile == null) {
            return;
        }
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            if (path != null) {
                fields.put("path", path);
            }
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
