package com.herald.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-process registry of pending HITL approvals. Holds metadata for the
 * Console inbox and the {@link CompletableFuture} each gate blocks on.
 */
@Component
public class PendingApprovalRegistry {

    private record Entry(PendingApproval approval, CompletableFuture<Boolean> future) {}

    private final ConcurrentHashMap<String, Entry> pending = new ConcurrentHashMap<>();

    /**
     * Register a pending approval and return the future the gate should await.
     */
    public CompletableFuture<Boolean> register(PendingApproval approval) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(approval.id(), new Entry(approval, future));
        return future;
    }

    public void remove(String id) {
        pending.remove(id);
    }

    public List<PendingApproval> listAll() {
        List<PendingApproval> out = new ArrayList<>();
        for (Entry e : pending.values()) {
            out.add(e.approval());
        }
        out.sort(Comparator.comparing(PendingApproval::createdAt));
        return out;
    }

    public List<PendingApproval> listForConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return listAll();
        }
        return listAll().stream()
                .filter(a -> conversationId.equals(a.conversationId()))
                .toList();
    }

    /**
     * Resolve a pending approval. Returns {@code false} if the id is unknown or
     * already completed.
     */
    public boolean resolve(String approvalId, boolean approved) {
        Entry entry = pending.get(approvalId);
        if (entry == null) {
            return false;
        }
        entry.future().complete(approved);
        return true;
    }
}
