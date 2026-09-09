package com.herald.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldAutoMemoryAdvisorTest {

    @TempDir
    Path memoriesDir;

    @Test
    void buildsWrappedToolCallbacksForMutatingOps() {
        var advisor = HeraldAutoMemoryAdvisor.builder()
                .memoriesRootDirectory(memoriesDir)
                .logFile(memoriesDir.resolve("log.md"))
                .build();

        List<ToolCallback> callbacks = advisor.memoryToolCallbacks();
        assertThat(callbacks).isNotEmpty();

        for (ToolCallback cb : callbacks) {
            String name = cb.getToolDefinition().name();
            if (LoggingMemoryToolCallback.isMutatingMemoryTool(name)) {
                assertThat(cb)
                        .as("mutating tool %s should be wrapped", name)
                        .isInstanceOf(LoggingMemoryToolCallback.class);
            } else {
                assertThat(cb)
                        .as("read-only tool %s should not be wrapped", name)
                        .isNotInstanceOf(LoggingMemoryToolCallback.class);
            }
        }
    }

    @Test
    void injectsMemorySystemPromptAndMergesToolCallbacksWhenToolOptionsPresent() {
        var advisor = HeraldAutoMemoryAdvisor.builder()
                .memoriesRootDirectory(memoriesDir)
                .logFile(memoriesDir.resolve("log.md"))
                .build();

        AnthropicChatOptions options = AnthropicChatOptions.builder().build();
        Prompt prompt = new Prompt(List.of(new SystemMessage("You are Herald.")), options);
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        ChatClientRequest after = advisor.before(request, null);

        String system = after.prompt().getSystemMessage().getText();
        assertThat(system)
                .contains("You are Herald.")
                .containsIgnoringCase("memory");

        var merged = ((AnthropicChatOptions) after.prompt().getOptions()).getToolCallbacks();
        assertThat(merged).isNotEmpty();
        assertThat(merged.stream().map(c -> c.getToolDefinition().name()))
                .contains("MemoryCreate", "MemoryView");
    }

    @Test
    void passesThroughWhenOptionsLackToolCalling() {
        var advisor = HeraldAutoMemoryAdvisor.builder()
                .memoriesRootDirectory(memoriesDir)
                .build();

        Prompt prompt = new Prompt(new SystemMessage("You are Herald."));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        ChatClientRequest after = advisor.before(request, null);

        assertThat(after).isSameAs(request);
    }
    @Test
    void actualReadIsMarkedDuringCallAndClearedAfterCompletion() throws Exception {
        java.nio.file.Files.writeString(memoriesDir.resolve("a.md"), "A memory about error, not found, declined, not confirmed, rejected and does not exist.");
        var advisor = HeraldAutoMemoryAdvisor.builder().memoriesRootDirectory(memoriesDir).build();
        var chain = org.mockito.Mockito.mock(org.springframework.ai.chat.client.advisor.api.CallAdvisorChain.class);
        org.mockito.Mockito.when(chain.nextCall(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ChatClientRequest request = invocation.getArgument(0);
            var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) request.prompt().getOptions();
            var read = options.getToolCallbacks().stream().filter(cb -> cb.getToolDefinition().name().equals("MemoryView")).findFirst().orElseThrow();
            assertThat(read.call("{\"path\":\"a.md\",\"viewRange\":\"bad,range\"}")).contains("Error:");
            assertThat(contextSnapshot().getProperty("path.0")).isNull();
            assertThat(read.call("{\"path\":\"a.md\",\"viewRange\":null}")).contains("A memory");
            var props = contextSnapshot();
            assertThat(props.getProperty("active")).isEqualTo("true");
            assertThat(props.getProperty("path.0")).isEqualTo("a.md");
            return null;
        });
        advisor.adviseCall(new ChatClientRequest(new Prompt(new SystemMessage("Herald"), AnthropicChatOptions.builder().build()), Map.of("chat_memory_conversation_id", "web-test")), chain);
        assertThat(contextSnapshot().getProperty("active")).isEqualTo("false");
    }
    @Test
    void independentStreamsRemainTrackedWhenCompletionSwitchesScheduler() throws Exception {
        java.nio.file.Files.writeString(memoriesDir.resolve("a.md"), "A memory");
        var advisor = HeraldAutoMemoryAdvisor.builder().memoriesRootDirectory(memoriesDir).build();
        var chain = org.mockito.Mockito.mock(org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(chain.nextStream(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ChatClientRequest request = invocation.getArgument(0);
            assertThat(request.context().get("herald.memory.evidence")).isInstanceOf(MemoryContextEvidence.class);
            var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) request.prompt().getOptions();
            var read = options.getToolCallbacks().stream().filter(cb -> cb.getToolDefinition().name().equals("MemoryView")).findFirst().orElseThrow();
            read.call("{\"path\":\"a.md\",\"viewRange\":null}");
            assertThat(contextSnapshot().getProperty("path.0")).isEqualTo("a.md");
            calls.incrementAndGet();
            return reactor.core.publisher.Mono.delay(java.time.Duration.ofMillis(10), reactor.core.scheduler.Schedulers.parallel())
                    .thenMany(reactor.core.publisher.Flux.<org.springframework.ai.chat.client.ChatClientResponse>empty());
        });
        for (int i = 0; i < 2; i++) {
            var request = new ChatClientRequest(new Prompt(new SystemMessage("Herald"), AnthropicChatOptions.builder().build()), Map.of("chat_memory_conversation_id", "web-test"));
            advisor.adviseStream(request, chain).blockLast(java.time.Duration.ofSeconds(2));
        }
        assertThat(calls.get()).isEqualTo(2);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(contextSnapshot().getProperty("active")).isEqualTo("false"));
    }

    private java.util.Properties contextSnapshot() throws Exception {
        java.util.Properties props = new java.util.Properties();
        try(var list = java.nio.file.Files.list(memoriesDir.resolve(".memory-context"))) {
            try(var in = java.nio.file.Files.newInputStream(list.findFirst().orElseThrow())) { props.load(in); }
        }
        return props;
    }

}
