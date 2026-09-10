package com.herald.agent;

import com.herald.config.HeraldConfig.CompactionStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class TurnSafeCompactionTest {
    private TurnSafeChatMemory memory() { return new TurnSafeChatMemory(new InMemoryChatMemoryRepository(), 100); }
    private ContextCompactionAdvisor advisor(TurnSafeChatMemory memory, ChatModel model, CompactionStrategy strategy) {
        return new ContextCompactionAdvisor(memory, model, 100, null, null, strategy);
    }
    private ChatResponse response(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    private List<Message> turns() {
        return new ArrayList<>(List.of(new UserMessage("a".repeat(400)), new AssistantMessage("b".repeat(400)),
                new UserMessage("c".repeat(400)), new AssistantMessage("d".repeat(400)),
                new UserMessage("e".repeat(400)), new AssistantMessage("f".repeat(400))));
    }

    @Test void naiveCutInsideToolPairSnapsBackToUser() {
        var history = new ArrayList<Message>(List.of(new UserMessage("old"), new AssistantMessage("done"),
                new UserMessage("keep"), AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("id", "function", "read", "{}"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("id", "read", "data"))).build(),
                new AssistantMessage("end"), new UserMessage("latest")));
        assertThat(TurnSafeChatMemory.priorUserBoundary(history, 4)).isEqualTo(2);
        assertThat(TurnSafeChatMemory.window(history, 3)).containsExactlyElementsOf(history.subList(2, 7));
        var memory = memory(); memory.add("c", history);
        advisor(memory, null, CompactionStrategy.SLIDING_WINDOW).forceCompact("c");
        var after = memory.get("c");
        assertThat(after.getFirst()).isInstanceOf(UserMessage.class);
        if (after.contains(history.get(4))) assertThat(after).contains(history.get(3));
    }

    @Test void repeatedSummaryIsInputToNextPassAndSurvivesPromptSanitizerAndCountWindow() {
        var memory = memory(); memory.add("c", turns());
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("first summary"), response("second summary"));
        var advisor = advisor(memory, model, CompactionStrategy.RECURSIVE_SUMMARY);
        advisor.forceCompact("c");
        assertThat(memory.get("c").get(1).getMetadata()).containsEntry(ContextCompactionAdvisor.SYNTHETIC, true);
        memory.add("c", List.of(new UserMessage("g".repeat(400)), new AssistantMessage("h".repeat(400))));
        advisor.forceCompact("c");
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getContents()).contains("first summary");
        assertThat(memory.get("c").stream().filter(m -> m instanceof AssistantMessage && TurnSafeChatMemory.isSynthetic(m)))
                .hasSize(1);
        var chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> {
            ChatClientRequest request = inv.getArgument(0);
            assertThat(LeadingTurnSanitizingAdvisor.trimLeadingNonUser(request.prompt().getInstructions()))
                    .anyMatch(m -> "second summary".equals(m.getText()));
            return mock(ChatClientResponse.class);
        });
        new OneShotMemoryAdvisor(memory, 3).adviseCall(
                new ChatClientRequest(new Prompt(new UserMessage("next")), Map.of("chat_memory_conversation_id", "c")), chain);
    }

    @Test void slidingWindowNeverCallsSummaryModel() {
        var memory = memory(); memory.add("c", turns());
        var model = mock(ChatModel.class);
        advisor(memory, model, CompactionStrategy.SLIDING_WINDOW).forceCompact("c");
        verifyNoInteractions(model);
        assertThat(memory.get("c")).hasSizeLessThan(6);
    }

    @Test void missingEmptyAndFailedSummaryLeaveHistoryIntact() {
        var memory = memory(); var original = turns(); memory.add("c", original);
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("offline"));
        assertThat(advisor(memory, model, CompactionStrategy.RECURSIVE_SUMMARY).forceCompact("c")).contains("unchanged");
        when(model.call(any(Prompt.class))).thenReturn(response("  "));
        advisor(memory, model, CompactionStrategy.RECURSIVE_SUMMARY).forceCompact("c");
        advisor(memory, null, CompactionStrategy.RECURSIVE_SUMMARY).forceCompact("c");
        assertThat(memory.get("c")).containsExactlyElementsOf(original);
    }

    @Test void latestHugeTurnAndSystemMessagesRemainWhole() {
        var memory = memory(); var system = new SystemMessage("instructions");
        var latest = new UserMessage("x".repeat(4000));
        memory.add("c", List.of(system, new UserMessage("old"), new AssistantMessage("done"), latest, new AssistantMessage("answer")));
        advisor(memory, null, CompactionStrategy.SLIDING_WINDOW).forceCompact("c");
        assertThat(memory.get("c")).containsExactly(system, latest, new AssistantMessage("answer"));
        assertThat(advisor(memory, null, CompactionStrategy.SLIDING_WINDOW).forceCompact("c")).contains("unchanged");
    }

    @Test void insufficientSafeBoundaryLeavesFirstTurnUntouched() {
        var memory = memory();
        var original = List.<Message>of(new UserMessage("x".repeat(4000)), new AssistantMessage("small"), new UserMessage("latest"));
        memory.add("c", original);
        assertThat(advisor(memory, null, CompactionStrategy.SLIDING_WINDOW).forceCompact("c")).contains("unchanged");
        assertThat(memory.get("c")).containsExactlyElementsOf(original);
    }

    @Test void streamFailureDoesNotLeakCompactionGuardIntoNextRequest() {
        var memory = memory(); memory.add("c", turns());
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("summary"));
        var advisor = advisor(memory, model, CompactionStrategy.RECURSIVE_SUMMARY);
        var chain = mock(org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(reactor.core.publisher.Flux.error(new IllegalStateException("stream failed")));
        var request = new ChatClientRequest(new Prompt(new UserMessage("next")), Map.of("chat_memory_conversation_id", "c"));
        assertThatThrownBy(() -> advisor.adviseStream(request, chain).blockLast()).hasMessage("stream failed");
        memory.add("c", turns());
        assertThatThrownBy(() -> advisor.adviseStream(request, chain).blockLast()).hasMessage("stream failed");
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test void overlappingAppendCannotBeLostDuringSummaryReplacement() throws Exception {
        var memory = memory(); memory.add("c", turns());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(inv -> { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return response("summary"); });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var compact = executor.submit(() -> advisor(memory, model, CompactionStrategy.RECURSIVE_SUMMARY).forceCompact("c"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var append = executor.submit(() -> memory.add("c", new UserMessage("concurrent")));
            release.countDown(); compact.get(5, TimeUnit.SECONDS); append.get(5, TimeUnit.SECONDS);
            assertThat(memory.get("c")).anyMatch(m -> "concurrent".equals(m.getText()));
        }
    }
}
