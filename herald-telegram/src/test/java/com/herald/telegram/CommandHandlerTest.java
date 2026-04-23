package com.herald.telegram;

import com.herald.agent.AgentService;
import com.herald.agent.ApprovalGate;
import com.herald.agent.ModelSwitcher;
import com.herald.agent.ReloadableSkillsTool;
import com.herald.agent.UsageTracker;
import reactor.core.publisher.Flux;
import com.herald.cron.CronJob;
import com.herald.cron.CronService;
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

    private CronService cronService;
    private ChatMemory chatMemory;
    private TelegramSender sender;
    private UsageTracker usageTracker;
    private ModelSwitcher modelSwitcher;
    private ReloadableSkillsTool reloadableSkillsTool;
    private ApprovalGate approvalGate;
    private AgentService agentService;
    private com.herald.agent.ContextCompactionAdvisor compactionAdvisor;
    private com.herald.agent.PromptDumpAdvisor promptDumpAdvisor;
    private CommandHandler handler;

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
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
        approvalGate = mock(ApprovalGate.class);
        agentService = mock(AgentService.class);
        when(agentService.streamChat(anyString(), anyString())).thenReturn(Flux.empty());
        compactionAdvisor = mock(com.herald.agent.ContextCompactionAdvisor.class);
        promptDumpAdvisor = new com.herald.agent.PromptDumpAdvisor(false);
        handler = new CommandHandler(cronService, chatMemory, sender, usageTracker, modelSwitcher,
                List.of("memory", "shell", "filesystem", "todo", "ask", "task", "taskOutput", "skills", "cron"),
                reloadableSkillsTool, agentService, 200_000, approvalGate,
                java.util.Optional.of(compactionAdvisor), promptDumpAdvisor);
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
        handler.handle("/status");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("anthropic/claude-sonnet-4-5")
                        && msg.contains("Uptime") && msg.contains("Active tools: 9")));
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
    void debugShowsContextSizeAndTools() {
        Message mockMsg = mock(Message.class);
        when(mockMsg.getText()).thenReturn("Hello world test message");
        when(chatMemory.get("default"))
                .thenReturn(List.of(mockMsg, mockMsg, mockMsg));
        handler.handle("/debug");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("Context messages: 3")
                        && msg.contains("Context size:")
                        && msg.contains("tokens")
                        && msg.contains("Active tools")));
    }

    // --- /memory ---

    @Test
    void memoryCommandShowsAgentManagedMessage() {
        handler.handle("/memory list");
        verify(sender).sendMessage(argThat(msg -> msg.contains("AutoMemoryTools") || msg.contains("agent")));
    }

    @Test
    void memoryWithNoSubcommandShowsAgentMessage() {
        handler.handle("/memory");
        verify(sender).sendMessage(argThat(msg -> msg.contains("AutoMemoryTools") || msg.contains("agent")));
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

    // --- /cron edit ---

    @Test
    void cronEditUpdatesSchedule() {
        CronJob existing = new CronJob(1, "morning-briefing", "0 0 7 * * MON-FRI", "prompt",
                null, true, true);
        when(cronService.findJob("morning-briefing")).thenReturn(existing);
        when(cronService.rescheduleJob("morning-briefing", "0 0 8 * * MON-FRI"))
                .thenReturn(new CronJob(1, "morning-briefing", "0 0 8 * * MON-FRI", "prompt",
                        null, true, true));

        handler.handle("/cron edit morning-briefing schedule 0 0 8 * * MON-FRI");

        verify(cronService).rescheduleJob("morning-briefing", "0 0 8 * * MON-FRI");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("Updated schedule") && msg.contains("morning-briefing")
                        && msg.contains("0 0 8 * * MON-FRI")));
    }

    @Test
    void cronEditWithNonexistentJobShowsError() {
        when(cronService.findJob("nonexistent")).thenReturn(null);

        handler.handle("/cron edit nonexistent schedule 0 0 8 * * *");

        verify(cronService, never()).rescheduleJob(any(), any());
        verify(sender).sendMessage(argThat(msg -> msg.contains("not found")));
    }

    @Test
    void cronEditWithMissingFieldShowsUsage() {
        handler.handle("/cron edit morning-briefing");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    @Test
    void cronEditWithUnsupportedFieldShowsUsage() {
        handler.handle("/cron edit morning-briefing prompt new prompt");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    // --- /cron list built-in flag ---

    @Test
    void cronListShowsBuiltInFlag() {
        when(cronService.listJobs()).thenReturn(List.of(
                new CronJob(1, "morning-briefing", "0 0 7 * * MON-FRI", "prompt",
                        null, true, true),
                new CronJob(2, "custom-job", "0 0 9 * * *", "prompt",
                        null, true, false)));
        handler.handle("/cron list");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("morning-briefing") && msg.contains("built-in")
                        && msg.contains("custom-job") && !msg.contains("custom-job* | `0 0 9 * * *` | enabled | last run: never | built-in")));
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

    // --- /confirm ---

    @Test
    void confirmApprovedResolvesApproval() {
        when(approvalGate.resolve("abc-123", true)).thenReturn(true);
        assertThat(handler.handle("/confirm abc-123 yes")).isTrue();
        verify(approvalGate).resolve("abc-123", true);
    }

    @Test
    void confirmDeniedResolvesApproval() {
        when(approvalGate.resolve("abc-123", false)).thenReturn(true);
        assertThat(handler.handle("/confirm abc-123 no")).isTrue();
        verify(approvalGate).resolve("abc-123", false);
    }

    @Test
    void confirmUnknownIdSendsError() {
        when(approvalGate.resolve("unknown-id", true)).thenReturn(false);
        handler.handle("/confirm unknown-id yes");
        verify(sender).sendMessage(argThat(msg -> msg.contains("No pending approval")));
    }

    @Test
    void confirmWithNoArgsShowsUsage() {
        handler.handle("/confirm");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    @Test
    void helpIncludesConfirmCommand() {
        handler.handle("/help");
        verify(sender).sendMessage(argThat(msg -> msg.contains("/confirm")));
    }

    // --- /save ---

    @Test
    void saveWithEmptyHistoryShortCircuitsWithoutAgent() {
        when(chatMemory.get(anyString())).thenReturn(Collections.emptyList());
        handler.handle("/save");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Nothing to save")));
        verifyNoInteractions(agentService);
    }

    @Test
    void saveDispatchesAgentWithWikiIngestPrompt() {
        Message userMsg = mock(Message.class);
        when(userMsg.getText()).thenReturn("hi");
        when(chatMemory.get(anyString())).thenReturn(List.of(userMsg));

        handler.handle("/save");

        verify(sender).sendTypingAction();
        verify(agentService).streamChat(argThat(prompt ->
                prompt.contains("wiki-ingest")
                        && prompt.contains("/save")
                        && prompt.toLowerCase().contains("sources/")), anyString());
    }

    @Test
    void saveWithNameForwardsSlugHintToAgent() {
        when(chatMemory.get(anyString())).thenReturn(List.of(mock(Message.class)));

        handler.handle("/save phase-a-plan");

        verify(agentService).streamChat(argThat(prompt ->
                prompt.contains("phase-a-plan")), anyString());
    }

    @Test
    void saveSanitizesSlugHint() {
        when(chatMemory.get(anyString())).thenReturn(List.of(mock(Message.class)));

        handler.handle("/save <script>alert(1)</script>");

        verify(agentService).streamChat(argThat(prompt ->
                !prompt.contains("<script>") && !prompt.contains("</script>")), anyString());
    }

    @Test
    void extractSaveNameParsesArgument() {
        assertThat(CommandHandler.extractSaveName("/save")).isNull();
        assertThat(CommandHandler.extractSaveName("/save   ")).isNull();
        assertThat(CommandHandler.extractSaveName("/save foo")).isEqualTo("foo");
        assertThat(CommandHandler.extractSaveName("/save multi word name"))
                .isEqualTo("multi word name");
    }

    @Test
    void helpIncludesSaveCommand() {
        handler.handle("/help");
        verify(sender).sendMessage(argThat(msg -> msg.contains("/save")));
    }

    // --- /think (#307) ---

    @Test
    void thinkWithoutArgShowsUsage() {
        handler.handle("/think");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    @Test
    void thinkStatusShowsCurrentTier() {
        when(modelSwitcher.getThinkingTier())
                .thenReturn(com.herald.agent.ModelSwitcher.ThinkingTier.OFF);
        handler.handle("/think status");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("OFF") && msg.contains("budget")));
    }

    @Test
    void thinkHighInvokesModelSwitcher() {
        handler.handle("/think high");
        verify(modelSwitcher).setThinkingTier(com.herald.agent.ModelSwitcher.ThinkingTier.HIGH);
    }

    @Test
    void thinkOffInvokesModelSwitcher() {
        handler.handle("/think off");
        verify(modelSwitcher).setThinkingTier(com.herald.agent.ModelSwitcher.ThinkingTier.OFF);
    }

    @Test
    void thinkUnknownTierRejects() {
        handler.handle("/think ludicrous");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Unknown thinking tier")));
        verify(modelSwitcher, never()).setThinkingTier(any());
    }

    @Test
    void thinkNonAnthropicWarnsButProceeds() {
        when(modelSwitcher.getActiveProvider()).thenReturn("openai");
        handler.handle("/think high");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Anthropic-only")));
        verify(modelSwitcher).setThinkingTier(com.herald.agent.ModelSwitcher.ThinkingTier.HIGH);
    }

    // --- /compact (#307) ---

    @Test
    void compactWithoutAdvisorReportsDisabled() {
        // Rebuild handler with an empty compaction advisor Optional.
        com.herald.telegram.CommandHandler noCompact = new CommandHandler(
                cronService, chatMemory, sender, usageTracker, modelSwitcher,
                List.of(), reloadableSkillsTool, agentService, 200_000, approvalGate,
                java.util.Optional.empty(), promptDumpAdvisor);

        noCompact.handle("/compact");

        verify(sender).sendMessage(argThat(msg -> msg.contains("persistence")));
    }

    @Test
    void compactNowDelegatesToAdvisor() {
        when(compactionAdvisor.forceCompact(anyString())).thenReturn("Compacted 10 → 5 messages.");
        handler.handle("/compact");
        verify(compactionAdvisor).forceCompact(anyString());
        verify(sender).sendMessage(argThat(msg -> msg.contains("Forced compaction")));
    }

    @Test
    void compactStatusShowsMetrics() {
        when(compactionAdvisor.getStatus(anyString()))
                .thenReturn(new com.herald.agent.ContextCompactionAdvisor.CompactionStatus(
                        42, 5000, 100_000, 80_000));
        handler.handle("/compact status");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("Messages: 42") && msg.contains("5")));
    }

    // --- /trace (#307) ---

    @Test
    void traceWithoutArgShowsUsage() {
        handler.handle("/trace");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    @Test
    void traceOnEnablesDumpAdvisor() {
        handler.handle("/trace on");
        assertThat(promptDumpAdvisor.isEnabled()).isTrue();
    }

    @Test
    void traceOffDisablesDumpAdvisor() {
        promptDumpAdvisor.setEnabled(true);
        handler.handle("/trace off");
        assertThat(promptDumpAdvisor.isEnabled()).isFalse();
    }

    @Test
    void traceStatusReportsCurrentState() {
        handler.handle("/trace status");
        verify(sender).sendMessage(argThat(msg -> msg.contains("trace")));
    }

    @Test
    void helpIncludesOperatorCommands() {
        handler.handle("/help");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("/think") && msg.contains("/compact") && msg.contains("/trace")));
    }
}
