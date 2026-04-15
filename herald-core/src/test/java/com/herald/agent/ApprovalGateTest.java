package com.herald.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ApprovalGateTest {

    @Test
    void approvedRequestReturnsApproved() throws Exception {
        MessageSender mockSender = mock(MessageSender.class);
        CountDownLatch messageSent = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); messageSent.countDown(); return null; })
                .when(mockSender).sendMessage(anyString());

        ApprovalGate gate = new ApprovalGate(Optional.of(mockSender), 5);

        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> gate.requestApproval("Run skill: weather"));

        assertThat(messageSent.await(5, TimeUnit.SECONDS)).isTrue();
        String approvalId = extractApprovalId(captured.get());
        gate.resolve(approvalId, true);

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("APPROVED");
    }

    @Test
    void deniedRequestReturnsDenied() throws Exception {
        MessageSender mockSender = mock(MessageSender.class);
        CountDownLatch messageSent = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); messageSent.countDown(); return null; })
                .when(mockSender).sendMessage(anyString());

        ApprovalGate gate = new ApprovalGate(Optional.of(mockSender), 5);

        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> gate.requestApproval("Run skill: broadcom"));

        assertThat(messageSent.await(5, TimeUnit.SECONDS)).isTrue();
        String approvalId = extractApprovalId(captured.get());
        gate.resolve(approvalId, false);

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("DENIED");
    }

    @Test
    void timeoutReturnTimeout() {
        MessageSender mockSender = mock(MessageSender.class);
        ApprovalGate gate = new ApprovalGate(Optional.of(mockSender), 1);

        String result = gate.requestApproval("Run skill: slow");

        assertThat(result).isEqualTo("TIMEOUT");
    }

    @Test
    void noSenderReturnsDenied() {
        ApprovalGate gate = new ApprovalGate(Optional.empty(), 5);

        String result = gate.requestApproval("Run skill: nope");

        assertThat(result).isEqualTo("DENIED");
    }

    @Test
    void resolveReturnsFalseForUnknownId() {
        ApprovalGate gate = new ApprovalGate(Optional.empty(), 5);

        assertThat(gate.resolve("nonexistent-id", true)).isFalse();
    }

    @Test
    void messageContainsConfirmInstructions() throws Exception {
        MessageSender mockSender = mock(MessageSender.class);
        CountDownLatch messageSent = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); messageSent.countDown(); return null; })
                .when(mockSender).sendMessage(anyString());

        ApprovalGate gate = new ApprovalGate(Optional.of(mockSender), 1);
        gate.requestApproval("Test action");

        assertThat(messageSent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get()).contains("/confirm");
        assertThat(captured.get()).contains("yes");
        assertThat(captured.get()).contains("no");
        assertThat(captured.get()).contains("Test action");
    }

    private static String extractApprovalId(String message) {
        return message.split("/confirm ")[1].split(" ")[0];
    }
}
