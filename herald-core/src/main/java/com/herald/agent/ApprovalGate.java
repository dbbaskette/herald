package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGate.class);

    private final MessageSender messageSender;
    private final int timeoutSeconds;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();

    @Autowired
    public ApprovalGate(Optional<MessageSender> messageSender,
                        @Value("${herald.agent.approval-timeout-seconds:60}") int timeoutSeconds) {
        this.messageSender = messageSender.orElse(null);
        this.timeoutSeconds = timeoutSeconds;
    }

    public String requestApproval(String description) {
        if (messageSender == null) {
            log.warn("MessageSender not available; auto-denying approval for: {}", description);
            return "DENIED";
        }

        String approvalId = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingApprovals.put(approvalId, future);

        messageSender.sendMessage("Approval required:\n" + description + "\n"
                + "Reply: /confirm " + approvalId + " yes  OR  /confirm " + approvalId + " no");

        try {
            Boolean approved = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(approved)) {
                log.info("Approved: {}", description);
                return "APPROVED";
            }
            log.info("Denied: {}", description);
            return "DENIED";
        } catch (TimeoutException e) {
            log.warn("Approval timed out after {}s: {}", timeoutSeconds, description);
            return "TIMEOUT";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "DENIED";
        } catch (Exception e) {
            log.error("Error during approval for: {} — {}", description, e.getMessage());
            return "DENIED";
        } finally {
            pendingApprovals.remove(approvalId);
        }
    }

    public boolean resolve(String approvalId, boolean approved) {
        CompletableFuture<Boolean> future = pendingApprovals.get(approvalId);
        if (future != null) {
            future.complete(approved);
            return true;
        }
        return false;
    }
}
