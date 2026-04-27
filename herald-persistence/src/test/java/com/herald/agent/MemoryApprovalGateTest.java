package com.herald.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryApprovalGateTest {

    private static class CapturingSink implements MemoryApprovalGate.MessageSenderLike {
        final List<String> messages = new ArrayList<>();
        @Override public void sendMessage(String text) { messages.add(text); }
    }

    @Test
    void autoModeAppliesWithoutPrompt(@TempDir Path tempDir) {
        CapturingSink sink = new CapturingSink();
        MemoryApprovalGate gate = new MemoryApprovalGate(
                sink, MemoryApprovalPolicy.disabled(), tempDir, tempDir.resolve("log.md"));

        MemoryApprovalGate.Decision result = gate.evaluate("memoryStrReplace",
                "{\"path\":\"concepts/foo.md\",\"old_str\":\"a\",\"new_str\":\"b\"}");

        assertThat(result).isEqualTo(MemoryApprovalGate.Decision.APPLY);
        assertThat(sink.messages).isEmpty();
    }

    @Test
    void confirmDiffSendsDiffAndBlocksUntilApproved(@TempDir Path tempDir) throws Exception {
        CapturingSink sink = new CapturingSink();
        MemoryApprovalPolicy policy = new MemoryApprovalPolicy(
                Map.of("concept", MemoryApprovalPolicy.Mode.CONFIRM_DIFF),
                MemoryApprovalPolicy.Mode.CONFIRM_DIFF, MemoryApprovalPolicy.Mode.CONFIRM_DIFF,
                MemoryApprovalPolicy.Mode.AUTO, 5);
        MemoryApprovalGate gate = new MemoryApprovalGate(
                sink, policy, tempDir, tempDir.resolve("log.md"));

        // Pre-create the page so resolvePageType returns "concept".
        Path page = tempDir.resolve("concepts/hot.md");
        Files.createDirectories(page.getParent());
        Files.writeString(page, "---\ntype: concept\n---\noriginal");

        // Run the gate on a background thread; resolve from the test thread.
        CompletableFuture<MemoryApprovalGate.Decision> result = CompletableFuture.supplyAsync(
                () -> gate.evaluate("memoryStrReplace",
                        "{\"path\":\"concepts/hot.md\",\"old_str\":\"original\",\"new_str\":\"new\"}"),
                Executors.newSingleThreadExecutor());

        // Wait for the prompt to land before resolving — gate sends the message
        // synchronously inside evaluate(), so we just poll.
        for (int i = 0; i < 50 && sink.messages.isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertThat(sink.messages).hasSize(1);
        String approvalId = extractApprovalId(sink.messages.get(0));

        boolean resolved = gate.resolve(approvalId, true);
        assertThat(resolved).isTrue();

        assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo(MemoryApprovalGate.Decision.APPLY);
    }

    @Test
    void confirmDiffReturnsDiscardWhenUserDeclines(@TempDir Path tempDir) throws Exception {
        CapturingSink sink = new CapturingSink();
        MemoryApprovalPolicy policy = new MemoryApprovalPolicy(
                Map.of("concept", MemoryApprovalPolicy.Mode.CONFIRM_DIFF),
                MemoryApprovalPolicy.Mode.CONFIRM_DIFF, MemoryApprovalPolicy.Mode.CONFIRM_DIFF,
                MemoryApprovalPolicy.Mode.AUTO, 5);
        Path logFile = tempDir.resolve("log.md");
        MemoryApprovalGate gate = new MemoryApprovalGate(sink, policy, tempDir, logFile);

        CompletableFuture<MemoryApprovalGate.Decision> result = CompletableFuture.supplyAsync(
                () -> gate.evaluate("memoryStrReplace",
                        "{\"path\":\"concepts/x.md\",\"old_str\":\"a\",\"new_str\":\"b\"}"),
                Executors.newSingleThreadExecutor());

        for (int i = 0; i < 50 && sink.messages.isEmpty(); i++) {
            Thread.sleep(50);
        }
        gate.resolve(extractApprovalId(sink.messages.get(0)), false);

        assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo(MemoryApprovalGate.Decision.DISCARD);
        // log.md should record DISCARDED
        assertThat(Files.readString(logFile)).contains("DISCARDED");
    }

    @Test
    void confirmDiffReturnsTimeoutAfterPolicyWindow(@TempDir Path tempDir) throws Exception {
        CapturingSink sink = new CapturingSink();
        MemoryApprovalPolicy policy = new MemoryApprovalPolicy(
                Map.of("concept", MemoryApprovalPolicy.Mode.CONFIRM_DIFF),
                MemoryApprovalPolicy.Mode.CONFIRM_DIFF, MemoryApprovalPolicy.Mode.CONFIRM_DIFF,
                MemoryApprovalPolicy.Mode.AUTO, 1);
        Path logFile = tempDir.resolve("log.md");
        MemoryApprovalGate gate = new MemoryApprovalGate(sink, policy, tempDir, logFile);

        CompletableFuture<MemoryApprovalGate.Decision> result = CompletableFuture.supplyAsync(
                () -> gate.evaluate("memoryStrReplace",
                        "{\"path\":\"concepts/y.md\",\"old_str\":\"a\",\"new_str\":\"b\"}"),
                Executors.newSingleThreadExecutor());

        // Don't resolve — just wait for the 1s timeout to fire.
        assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo(MemoryApprovalGate.Decision.TIMEOUT);
        // log.md should record DISCARDED with reason=timeout
        assertThat(Files.readString(logFile)).contains("DISCARDED").contains("timeout");
    }

    @Test
    void noMessageSenderFallsBackToApply(@TempDir Path tempDir) {
        MemoryApprovalGate gate = new MemoryApprovalGate(
                null, MemoryApprovalPolicy.defaults(), tempDir, tempDir.resolve("log.md"));

        // CONFIRM_DIFF would normally fire for concepts, but with no sender we apply.
        MemoryApprovalGate.Decision result = gate.evaluate("memoryStrReplace",
                "{\"path\":\"concepts/foo.md\",\"old_str\":\"a\",\"new_str\":\"b\"}");

        assertThat(result).isEqualTo(MemoryApprovalGate.Decision.APPLY);
    }

    @Test
    void resolveReturnsFalseForUnknownId() {
        MemoryApprovalGate gate = new MemoryApprovalGate(
                m -> {}, MemoryApprovalPolicy.disabled(), null, null);

        assertThat(gate.resolve("not-a-real-id", true)).isFalse();
    }

    /** Extract the 8-char hex id from a "Reply: /confirm <id> yes …" prompt. */
    private static String extractApprovalId(String message) {
        int idx = message.indexOf("/confirm ");
        if (idx < 0) throw new IllegalStateException("no /confirm in message: " + message);
        return message.substring(idx + "/confirm ".length(),
                message.indexOf(' ', idx + "/confirm ".length()));
    }
}
