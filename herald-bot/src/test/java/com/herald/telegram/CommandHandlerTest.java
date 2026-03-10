package com.herald.telegram;

import com.herald.agent.ModelSwitcher;
import com.herald.agent.ReloadableSkillsTool;
import com.herald.agent.UsageTracker;
import com.herald.cron.CronJob;
import com.herald.cron.CronService;
import com.herald.memory.MemoryTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

import org.springframework.ai.chat.messages.Message;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CommandHandlerTest {

    private MemoryTools memoryTools;
    private CronService cronService;
    private ChatMemory chatMemory;
    private TelegramSender sender;
    private UsageTracker usageTracker;
    private ModelSwitcher modelSwitcher;
    private ReloadableSkillsTool reloadableSkillsTool;
    private CommandHandler handler;

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        memoryTools = mock(MemoryTools.class);
        cronService = mock(CronService.class);
        chatMemory = mock(ChatMemory.class);
        sender = mock(TelegramSender.class);
        usageTracker = mock(UsageTracker.class);
        modelSwitcher = mock(ModelSwitcher.class);
        when(modelSwitcher.getActiveProvider()).thenReturn("anthropic");
        when(modelSwitcher.getActiveModel()).thenReturn("claude-sonnet-4-5");
        when(modelSwitcher.getAvailableProviders()).thenReturn(Set.of("anthropic", "openai", "ollama"));
        when(chatMemory.get(anyString())).thenReturn(Collections.emptyList());
        reloadableSkillsTool = new ReloadableSkillsTool(tempDir.toString());
        handler = new CommandHandler(memoryTools, cronService, chatMemory, sender, usageTracker, modelSwitcher,
                List.of("memory", "shell", "filesystem", "todo", "ask", "task", "taskOutput", "skills", "cron"),
                reloadableSkillsTool, 200_000);
    }

    // --- handle() routing ---

    @Test
    void handleReturnsFalseForNonCommand() {
        assertThat(handler.handle("hello")).isFalse();
        verifyNoInteractions(sender);
    }

    @Test
    void handleReturnsFalseForNull() {
        assertThat(handler.handle(null)).isFalse();
        verifyNoInteractions(sender);
    }

    @Test
    void handleReturnsTrueForSlashCommand() {
        assertThat(handler.handle("/help")).isTrue();
        verify(sender).sendMessage(anyString());
    }

    // --- /help ---

    @Test
    void helpReturnsFormattedCommandList() {
        handler.handle("/help");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("/help") && msg.contains("/status")
                        && msg.contains("/reset") && msg.contains("/memory")
                        && msg.contains("/model status")
                        && msg.contains("/model <provider> <model>")
                        && msg.contains("/skills list")
                        && msg.contains("/skills reload")
                        && msg.contains("/cron list")
                        && msg.contains("/cron enable")
                        && msg.contains("/cron disable")));
    }

    @Test
    void helpIsCaseInsensitive() {
        handler.handle("/HELP");
        verify(sender).sendMessage(argThat(msg -> msg.contains("/help")));
    }

    // --- /status ---

    @Test
    void statusShowsUptimeModelAndToolCount() {
        when(memoryTools.count()).thenReturn(3);
        handler.handle("/status");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("anthropic/claude-sonnet-4-5") && msg.contains("3")
                        && msg.contains("Uptime") && msg.contains("Active tools: 8")));
    }

    // --- /reset ---

    @Test
    void resetClearsConversationHistory() {
        handler.handle("/reset");
        verify(chatMemory).clear("default");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("cleared") && msg.contains("Memory entries are preserved")));
    }

    // --- /debug ---

    @Test
    void debugShowsContextSizeMemoryCountAndTools() {
        when(memoryTools.count()).thenReturn(7);
        Message mockMsg = mock(Message.class);
        when(mockMsg.getText()).thenReturn("Hello world test message");
        when(chatMemory.get("default"))
                .thenReturn(List.of(mockMsg, mockMsg, mockMsg));
        handler.handle("/debug");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("Context messages: 3")
                        && msg.contains("Context size:")
                        && msg.contains("tokens")
                        && msg.contains("7") && msg.contains("Active tools")));
    }

    // --- /memory list ---

    @Test
    void memoryListDisplaysEntries() {
        when(memoryTools.memory_list()).thenReturn("- **name**: Dan\n- **role**: engineer");
        handler.handle("/memory list");
        verify(memoryTools).memory_list();
        verify(sender).sendMessage("- **name**: Dan\n- **role**: engineer");
    }

    // --- /memory set ---

    @Test
    void memorySetStoresValue() {
        when(memoryTools.memory_set("name", "Dan")).thenReturn("Stored memory: name = Dan");
        handler.handle("/memory set name Dan");
        verify(memoryTools).memory_set("name", "Dan");
        verify(sender).sendMessage("Stored memory: name = Dan");
    }

    @Test
    void memorySetWithMultiWordValue() {
        when(memoryTools.memory_set("project", "herald bot")).thenReturn("Stored memory: project = herald bot");
        handler.handle("/memory set project herald bot");
        verify(memoryTools).memory_set("project", "herald bot");
    }

    @Test
    void memorySetWithMissingValueShowsUsage() {
        handler.handle("/memory set name");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
        verify(memoryTools, never()).memory_set(any(), any());
    }

    // --- /memory clear (two-step confirmation) ---

    @Test
    void memoryClearWithoutConfirmShowsPrompt() {
        handler.handle("/memory clear");
        verify(memoryTools, never()).clearAll();
        verify(sender).sendMessage(argThat(msg -> msg.contains("Are you sure")));
    }

    @Test
    void memoryClearConfirmDeletesAllEntries() {
        // Must go through two-step flow: first /memory clear, then confirm
        handler.handle("/memory clear");
        handler.handle("/memory clear confirm");
        verify(memoryTools).clearAll();
        verify(sender).sendMessage(argThat(msg -> msg.contains("cleared")));
    }

    @Test
    void memoryClearConfirmWithoutPendingShowsError() {
        handler.handle("/memory clear confirm");
        verify(memoryTools, never()).clearAll();
        verify(sender).sendMessage(argThat(msg -> msg.contains("No pending confirmation")));
    }

    @Test
    void memoryClearTwoStepFlow() {
        // First call: prompts for confirmation
        handler.handle("/memory clear");
        verify(memoryTools, never()).clearAll();
        verify(sender).sendMessage(argThat(msg -> msg.contains("Are you sure")));

        // Second call with confirm: clears
        handler.handle("/memory clear confirm");
        verify(memoryTools).clearAll();
        verify(sender).sendMessage(argThat(msg -> msg.contains("cleared")));
    }

    @Test
    void memoryClearDoubleCallShowsAlreadyPending() {
        handler.handle("/memory clear");
        reset(sender);
        handler.handle("/memory clear");
        verify(memoryTools, never()).clearAll();
        verify(sender).sendMessage(argThat(msg -> msg.contains("Confirmation already pending")));
    }

    // --- /memory (no subcommand) ---

    @Test
    void memoryWithNoSubcommandShowsUsage() {
        handler.handle("/memory");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    // --- /memory unknown ---

    @Test
    void memoryUnknownSubcommandShowsUsage() {
        handler.handle("/memory foo");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Unknown memory subcommand")));
    }

    // --- /model status ---

    @Test
    void modelStatusShowsCurrentModelAndDailyUsage() {
        when(usageTracker.getDailyUsage())
                .thenReturn(new UsageTracker.UsageSummary(15000, 3000));
        when(usageTracker.getDailyUsageByAgent())
                .thenReturn(List.of(
                        new UsageTracker.AgentUsage("main", "anthropic", "claude-sonnet-4-5", 10000, 2000),
                        new UsageTracker.AgentUsage("research-agent", "anthropic", "claude-haiku-4-5", 5000, 1000)));
        when(usageTracker.estimateDailyCost()).thenReturn(new BigDecimal("0.0750"));

        handler.handle("/model status");

        verify(sender).sendMessage(argThat(msg ->
                msg.contains("anthropic/claude-sonnet-4-5")
                        && msg.contains("15.0K in")
                        && msg.contains("3.0K out")
                        && msg.contains("$0.0750")
                        && msg.contains("main")
                        && msg.contains("research-agent")));
    }

    @Test
    void modelStatusWithNoUsage() {
        when(usageTracker.getDailyUsage())
                .thenReturn(new UsageTracker.UsageSummary(0, 0));
        when(usageTracker.getDailyUsageByAgent()).thenReturn(List.of());
        when(usageTracker.estimateDailyCost()).thenReturn(BigDecimal.ZERO.setScale(4));

        handler.handle("/model status");

        verify(sender).sendMessage(argThat(msg ->
                msg.contains("Model Status") && msg.contains("0 in") && msg.contains("0 out")));
    }

    @Test
    void modelWithNoSubcommandShowsUsage() {
        handler.handle("/model");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    @Test
    void modelUnknownSubcommandShowsUsage() {
        handler.handle("/model foo");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    // --- /model <provider> <model> (switching) ---

    @Test
    void modelSwitchCallsModelSwitcher() {
        handler.handle("/model ollama qwen2.5-coder");

        verify(modelSwitcher).switchModel("ollama", "qwen2.5-coder");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("Switched") && msg.contains("ollama/qwen2.5-coder")));
    }

    @Test
    void modelSwitchToAnthropicModel() {
        handler.handle("/model anthropic claude-haiku-4-5");

        verify(modelSwitcher).switchModel("anthropic", "claude-haiku-4-5");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Switched")));
    }

    @Test
    void modelSwitchWithInvalidProviderShowsError() {
        doThrow(new IllegalArgumentException("Provider 'azure' is not configured. Available providers: [anthropic, openai, ollama]"))
                .when(modelSwitcher).switchModel("azure", "gpt-4");

        handler.handle("/model azure gpt-4");

        verify(sender).sendMessage(argThat(msg ->
                msg.contains("Error") && msg.contains("azure") && msg.contains("not configured")));
    }

    // --- unknown command ---

    @Test
    void unknownCommandReturnsHelpfulError() {
        handler.handle("/foo");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("Unknown command") && msg.contains("/help")));
    }

    // --- /skills list ---

    @Test
    void skillsListShowsLoadedSkills() throws IOException {
        Path skillDir = tempDir.resolve("weather");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                """
                ---
                name: weather
                description: Look up current weather conditions
                ---
                You are a weather skill.
                """);

        // Reload to pick up the newly created skill files
        reloadableSkillsTool.reload();

        handler.handle("/skills list");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("weather") && msg.contains("Look up current weather conditions")));
    }

    @Test
    void skillsListShowsEmptyWhenNoSkills() {
        handler.handle("/skills list");
        verify(sender).sendMessage(argThat(msg -> msg.contains("No skills currently loaded")));
    }

    // --- /skills reload ---

    @Test
    void skillsReloadReportsCount() throws IOException {
        Path skillDir = tempDir.resolve("gmail");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                """
                ---
                name: gmail
                description: Manage Gmail
                ---
                You are a gmail skill.
                """);

        handler.handle("/skills reload");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("Reloaded 1 skill(s)")));
    }

    // --- /skills (no subcommand) ---

    @Test
    void skillsWithNoSubcommandShowsUsage() {
        handler.handle("/skills");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    @Test
    void skillsUnknownSubcommandShowsUsage() {
        handler.handle("/skills foo");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Unknown skills subcommand")));
    }

    // --- /cron list ---

    @Test
    void cronListShowsJobs() {
        when(cronService.listJobs()).thenReturn(List.of(
                new CronJob(1, "morning-brief", "0 0 9 * * MON-FRI", "Give me a morning briefing",
                        java.time.LocalDateTime.of(2026, 3, 9, 9, 0), true, false),
                new CronJob(2, "weekly-review", "0 0 17 * * FRI", "Weekly review",
                        null, false, false)));
        handler.handle("/cron list");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("morning-brief") && msg.contains("enabled")
                        && msg.contains("weekly-review") && msg.contains("disabled")
                        && msg.contains("never")));
    }

    @Test
    void cronListShowsEmptyMessage() {
        when(cronService.listJobs()).thenReturn(List.of());
        handler.handle("/cron list");
        verify(sender).sendMessage(argThat(msg -> msg.contains("No cron jobs configured")));
    }

    // --- /cron enable ---

    @Test
    void cronEnableCallsService() {
        handler.handle("/cron enable morning-brief");
        verify(cronService).enableJob("morning-brief");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Enabled") && msg.contains("morning-brief")));
    }

    @Test
    void cronEnableWithNoNameShowsUsage() {
        handler.handle("/cron enable");
        verify(cronService, never()).enableJob(any());
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    // --- /cron disable ---

    @Test
    void cronDisableCallsService() {
        handler.handle("/cron disable morning-brief");
        verify(cronService).disableJob("morning-brief");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Disabled") && msg.contains("morning-brief")));
    }

    @Test
    void cronDisableWithNoNameShowsUsage() {
        handler.handle("/cron disable");
        verify(cronService, never()).disableJob(any());
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    // --- /cron (no subcommand / unknown) ---

    @Test
    void cronWithNoSubcommandShowsUsage() {
        handler.handle("/cron");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    @Test
    void cronUnknownSubcommandShowsUsage() {
        handler.handle("/cron foo");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Unknown cron subcommand")));
    }

    // --- formatUptime ---

    @Test
    void formatUptimeMinutesOnly() {
        assertThat(CommandHandler.formatUptime(300_000)).isEqualTo("5m");
    }

    @Test
    void formatUptimeHoursAndMinutes() {
        assertThat(CommandHandler.formatUptime(7_500_000)).isEqualTo("2h 5m");
    }

    @Test
    void formatUptimeDaysHoursMinutes() {
        assertThat(CommandHandler.formatUptime(90_300_000)).isEqualTo("1d 1h 5m");
    }
}
