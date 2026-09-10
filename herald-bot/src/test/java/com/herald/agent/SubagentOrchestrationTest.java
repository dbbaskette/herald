package com.herald.agent;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.common.task.subagent.*;
import org.springaicommunity.agent.tools.task.*;
import org.springaicommunity.agent.tools.task.claude.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class SubagentOrchestrationTest {
    private ChatResponse reply(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    private ChatResponse calls(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
    }
    private AssistantMessage.ToolCall call(String name, String arguments) {
        return new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", name, arguments);
    }
    private ChatModel model() {
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        return model;
    }
    private ToolCallback tool(String name) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name(name).description("role-only fixture").inputSchema("{}").build(); }
            public String call(String input) { return "result of " + name; }
        };
    }
    private List<String> results(Prompt prompt) {
        return prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(t -> t.getResponses().stream()).map(ToolResponseMessage.ToolResponse::responseData)
                .map(text -> text.startsWith("\"") ? new tools.jackson.databind.ObjectMapper().readValue(text, String.class) : text).toList();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realTaskToolsRunIsolatedWorkersInParallelAndSynthesizePartialFailures(boolean failWriter) {
        assertThat(runWorkers(failWriter)).contains(failWriter ? "Partial results preserved" : "synthesized response");
    }

    private String runWorkers(boolean failWriter) {
        var bothStarted = new CountDownLatch(2);
        var worker = model();
        when(worker.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            String system = prompt.getSystemMessage().getText();
            boolean research = system.contains("Research role");
            String expectedTool = research ? "researchTool" : "writerTool";
            assertThat(system).doesNotContain(research ? "Writer role" : "Research role", "supervisor secret");
            assertThat(prompt.getUserMessage().getText()).isEqualTo(research ? "research subtask" : "writer subtask");
            assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                    .extracting(t -> t.getToolDefinition().name()).containsExactly(expectedTool);
            if (results(prompt).isEmpty()) {
                bothStarted.countDown();
                assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
                if (!research && failWriter) throw new IllegalStateException("writer fixture failure");
                return calls(call(expectedTool, "{}"));
            }
            return reply(research ? "research complete" : "writing complete");
        });
        var references = List.of(new SubagentReference("research", "CLAUDE"), new SubagentReference("writer", "CLAUDE"));
        SubagentResolver resolver = new SubagentResolver() {
            public boolean canResolve(SubagentReference reference) { return reference.kind().equals("CLAUDE"); }
            public SubagentDefinition resolve(SubagentReference reference) {
                boolean research = reference.uri().equals("research");
                return new ClaudeSubagentDefinition(reference, Map.of("name", reference.uri(), "description", "fixture role",
                        "tools", research ? "researchTool" : "writerTool"), research ? "Research role" : "Writer role");
            }
        };
        var executor = new ClaudeSubagentExecutor(Map.of("default", SubagentToolCallbackCompatibility.wrap(
                ChatClient.builder(worker).defaultAdvisors(ExecutionAdvisors.create(ExecutionLimits.defaults(), () -> {})))),
                List.of(tool("researchTool"), tool("writerTool")), List.of());
        try (var repository = new OwnedTaskRepository()) {
            var task = TaskTool.builder().taskRepository(repository).subagentReferences(references)
                    .subagentTypes(new SubagentType(resolver, executor)).build();
            var output = TaskOutputTool.builder().taskRepository(repository).build();
            var supervisor = model(); var rounds = new AtomicInteger();
            when(supervisor.call(any(Prompt.class))).thenAnswer(inv -> {
                Prompt prompt = inv.getArgument(0);
                int round = rounds.getAndIncrement();
                if (round == 0) return calls(
                        call(task.getToolDefinition().name(), "{\"description\":\"research\",\"prompt\":\"research subtask\",\"subagent_type\":\"research\",\"run_in_background\":true}"),
                        call(task.getToolDefinition().name(), "{\"description\":\"writer\",\"prompt\":\"writer subtask\",\"subagent_type\":\"writer\",\"run_in_background\":true}"));
                if (round == 1) {
                    var taskIds = results(prompt).stream().filter(r -> r.startsWith("task_id: "))
                            .map(r -> r.substring(9, r.indexOf('\n'))).toList();
                    assertThat(taskIds).hasSize(2);
                    return calls(taskIds.stream().map(id -> call(output.getToolDefinition().name(),
                            "{\"task_id\":\"" + id + "\",\"block\":true,\"timeout\":5000}")).toArray(AssistantMessage.ToolCall[]::new));
                }
                String collected = String.join("\n", results(prompt));
                assertThat(collected).contains("research complete");
                assertThat(collected).contains(failWriter ? "writer fixture failure" : "writing complete");
                return reply(failWriter ? "Research complete; writer failed. Partial results preserved." : "Research and writing complete; synthesized response.");
            });
            var client = ChatClient.builder(supervisor).defaultSystem("supervisor secret")
                    .defaultToolCallbacks(task, output).defaultAdvisors(ExecutionAdvisors.create(ExecutionLimits.defaults(), () -> {})).build();
            String response = client.prompt().user("research then write").call().content();
            assertThat(rounds).hasValue(3);
            return response;
        }
    }

    @Test void telegramDeliversSynthesizedPartialResultsWithProgressToConfiguredOwner() {
        var bot = mock(com.pengrad.telegrambot.TelegramBot.class);
        var typingSent = new CompletableFuture<Void>();
        when(bot.execute(any(com.pengrad.telegrambot.request.SendChatAction.class))).thenAnswer(invocation -> {
            typingSent.complete(null);
            return null;
        });
        var sent = mock(com.pengrad.telegrambot.response.SendResponse.class);
        var telegramMessage = mock(com.pengrad.telegrambot.model.Message.class);
        when(sent.isOk()).thenReturn(true);
        when(sent.message()).thenReturn(telegramMessage);
        when(telegramMessage.messageId()).thenReturn(1);
        when(bot.execute(any(com.pengrad.telegrambot.request.SendMessage.class))).thenReturn(sent);
        var config = new com.herald.config.HeraldConfig(null,
                new com.herald.config.HeraldConfig.Telegram("fixture-token", "12345"),
                null, null, null, null, null, null, null, null);
        var sender = new com.herald.telegram.TelegramSender(bot, config, new com.herald.telegram.MessageFormatter());
        ChatChannelContext.set(ChatChannelContext.Channel.TELEGRAM, "12345");
        try {
            sender.sendStreamingMessage(reactor.core.publisher.Mono.fromSupplier(() -> {
                typingSent.orTimeout(7, TimeUnit.SECONDS).join();
                return runWorkers(true);
            }).flux());
        } finally { ChatChannelContext.clear(); }
        verify(bot).execute(any(com.pengrad.telegrambot.request.SendChatAction.class));
        var messages = org.mockito.ArgumentCaptor.forClass(com.pengrad.telegrambot.request.SendMessage.class);
        verify(bot).execute(messages.capture());
        assertThat(messages.getValue().getParameters().get("chat_id").toString()).isEqualTo("12345");
        assertThat(messages.getValue().getParameters().get("text").toString()).contains("Partial results preserved");
    }

    @Test void cancellingParentInterruptsWorkerAndPreventsOtherTurnReadingItsTask() throws Exception {
        var owner = new ExecutionState(ExecutionLimits.defaults());
        var other = new ExecutionState(ExecutionLimits.defaults());
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        try (var repository = new OwnedTaskRepository()) {
            org.springaicommunity.agent.tools.task.repository.BackgroundTask task;
            try (var scope = ExecutionState.attach(owner)) {
                ChatChannelContext.set(ChatChannelContext.Channel.WEB, "owner");
                try {
                    task = repository.putTask("owned", () -> {
                        assertThat(ExecutionState.current()).isSameAs(owner);
                        assertThat(ChatChannelContext.getConversationId()).isEqualTo("owner");
                        entered.countDown();
                        try { new CountDownLatch(1).await(); }
                        catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                        return "cancelled work";
                    });
                } finally { ChatChannelContext.clear(); }
                assertThat(repository.getTasks("owned")).isSameAs(task);
                var output = TaskOutputTool.builder().taskRepository(repository).build();
                assertThat(output.call("{\"task_id\":\"owned\",\"block\":true,\"timeout\":1}"))
                        .containsIgnoringCase("running");
                assertThat(task.isCancelled()).isFalse();
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try (var scope = ExecutionState.attach(other)) { assertThat(repository.getTasks("owned")).isNull(); }
            owner.cancel();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(task.isCancelled()).isTrue();
        }
    }
}
