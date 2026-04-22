package com.herald.telegram;

import com.herald.agent.AgentService;
import com.herald.agent.ApprovalGate;
import com.herald.agent.ModelSwitcher;
import com.herald.agent.UsageTracker;
import com.herald.cron.CronJob;
import com.herald.cron.CronService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.herald.agent.ReloadableSkillsTool;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private static final DateTimeFormatter CRON_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CronService cronService;
    private final ChatMemory chatMemory;
    private final TelegramSender sender;
    private final UsageTracker usageTracker;
    private final ModelSwitcher modelSwitcher;
    private final ReloadableSkillsTool reloadableSkillsTool;
    private final AgentService agentService;
    private final int activeToolsCount;
    private final int maxContextTokens;
    private final ApprovalGate approvalGate;

    public CommandHandler(CronService cronService, ChatMemory chatMemory,
                          TelegramSender sender, UsageTracker usageTracker, ModelSwitcher modelSwitcher,
                          @Qualifier("activeToolNames") List<String> activeToolNames,
                          ReloadableSkillsTool reloadableSkillsTool,
                          AgentService agentService,
                          @Value("${herald.agent.max-context-tokens:200000}") int maxContextTokens,
                          ApprovalGate approvalGate) {
        this.cronService = cronService;
        this.chatMemory = chatMemory;
        this.sender = sender;
        this.usageTracker = usageTracker;
        this.modelSwitcher = modelSwitcher;
        this.reloadableSkillsTool = reloadableSkillsTool;
        this.agentService = agentService;
        this.activeToolsCount = activeToolNames.size();
        this.maxContextTokens = maxContextTokens;
        this.approvalGate = approvalGate;
    }

    boolean handle(String text) {
        if (text == null || !text.startsWith("/")) {
            return false;
        }

        String[] parts = text.strip().split("\\s+", 4);
        String command = parts[0].toLowerCase();

        switch (command) {
            case "/help" -> handleHelp();
            case "/status" -> handleStatus();
            case "/reset" -> handleReset();
            case "/debug" -> handleDebug();
            case "/memory" -> handleMemory(parts);
            case "/model" -> handleModel(parts);
            case "/skills" -> handleSkills(parts);
            case "/cron" -> handleCron(parts);
            case "/confirm" -> handleConfirm(parts);
            case "/save" -> handleSave(text);
            default -> {
                sender.sendMessage("Unknown command: " + parts[0]
                        + "\nType /help to see available commands.");
            }
        }

        log.info("Handled slash command: {}", command);
        return true;
    }

    private void handleHelp() {
        String help = """
                *Available Commands*

                /help — Show this command list
                /status — System status: uptime, model, memory count
                /reset — Clear conversation history (memory is preserved)
                /debug — Show context size, memory entries, active tools
                /memory — Memory is now managed by the agent via long-term memory files
                /model status — Show current model and daily token usage
                /model <provider> <model> — Switch to a different model
                /skills list — List all loaded skills
                /skills reload — Reload skills from disk
                /confirm <id> yes|no — Approve or deny a pending action
                /save [name] — File this conversation into long-term memory (wiki-ingest)
                /cron list — List all scheduled cron jobs
                /cron enable <name> — Enable a cron job
                /cron disable <name> — Disable a cron job
                /cron edit <name> schedule <expr> — Update cron schedule
                """;
        sender.sendMessage(help);
    }

    private void handleStatus() {
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        String uptime = formatUptime(uptimeMillis);

        String modelName = modelSwitcher.getActiveProvider() + "/" + modelSwitcher.getActiveModel();
        String status = """
                *System Status*

                Uptime: %s
                Model: %s
                Active tools: %d
                """.formatted(uptime, modelName, activeToolsCount);
        sender.sendMessage(status);
    }

    private void handleReset() {
        chatMemory.clear(AgentService.DEFAULT_CONVERSATION_ID);
        sender.sendMessage("Conversation history cleared. Memory entries are preserved.");
        log.info("Conversation history cleared via /reset command");
    }

    private void handleDebug() {
        List<Message> messages = chatMemory.get(AgentService.DEFAULT_CONVERSATION_ID);
        int contextMessages = messages.size();
        int estimatedTokens = estimateTokens(messages);
        int ceiling = (int) (maxContextTokens * 0.8);
        int usagePercent = maxContextTokens > 0 ? (estimatedTokens * 100) / maxContextTokens : 0;

        String modelName = modelSwitcher.getActiveProvider() + "/" + modelSwitcher.getActiveModel();
        String debug = """
                *Debug Info*

                Context messages: %d
                Context size: ~%s tokens (%d%% of %s limit, ceiling %s)
                Active tools: %d
                Model: %s
                Available providers: %s
                """.formatted(contextMessages,
                formatTokens(estimatedTokens), usagePercent,
                formatTokens(maxContextTokens), formatTokens(ceiling),
                activeToolsCount, modelName,
                String.join(", ", modelSwitcher.getAvailableProviders()));
        sender.sendMessage(debug);
    }

    static int estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream()
                .mapToInt(m -> m.getText() != null ? m.getText().length() / 4 : 0)
                .sum();
    }

    private void handleMemory(String[] parts) {
        sender.sendMessage("Memory is now managed by the agent via long-term memory files (AutoMemoryTools). "
                + "Ask the agent directly to view or manage memories — e.g., \"what do you remember about me?\"");
    }

    private void handleModel(String[] parts) {
        if (parts.length < 2) {
            sender.sendMessage("Usage: /model status | /model <provider> <model>");
            return;
        }

        String subcommand = parts[1].toLowerCase();
        if ("status".equals(subcommand)) {
            handleModelStatus();
        } else if (parts.length >= 3) {
            // /model <provider> <model>
            handleModelSwitch(parts[1].toLowerCase(), parts[2]);
        } else {
            sender.sendMessage("Usage: /model status | /model <provider> <model>");
        }
    }

    private void handleSkills(String[] parts) {
        if (parts.length < 2) {
            sender.sendMessage("Usage: /skills list | /skills reload");
            return;
        }

        String subcommand = parts[1].toLowerCase();
        switch (subcommand) {
            case "list" -> {
                List<SkillsTool.Skill> skills = reloadableSkillsTool.getSkills();
                if (skills.isEmpty()) {
                    sender.sendMessage("No skills currently loaded.");
                    return;
                }
                var sb = new StringBuilder("*Loaded Skills*\n\n");
                for (SkillsTool.Skill skill : skills) {
                    String name = skill.name();
                    Object desc = skill.frontMatter().get("description");
                    sb.append("- *").append(name).append("*");
                    if (desc != null) {
                        sb.append(" — ").append(desc);
                    }
                    sb.append("\n");
                }
                sender.sendMessage(sb.toString());
            }
            case "reload" -> {
                int count = reloadableSkillsTool.reload();
                sender.sendMessage("Reloaded %d skill(s).".formatted(count));
                log.info("Skills reloaded from {}: {} skill(s)",
                        reloadableSkillsTool.getSkillsDirectory(), count);
            }
            default -> sender.sendMessage(
                    "Unknown skills subcommand: " + subcommand
                            + "\nUsage: /skills list | /skills reload");
        }
    }

    private void handleCron(String[] parts) {
        if (parts.length < 2) {
            sender.sendMessage("Usage: /cron list | enable | disable | edit <name> schedule <expr>");
            return;
        }

        String subcommand = parts[1].toLowerCase();
        switch (subcommand) {
            case "list" -> {
                List<CronJob> jobs = cronService.listJobs();
                if (jobs.isEmpty()) {
                    sender.sendMessage("No cron jobs configured.");
                    return;
                }
                var sb = new StringBuilder("*Cron Jobs*\n\n");
                for (CronJob job : jobs) {
                    sb.append("- *").append(job.name()).append("* | `")
                            .append(job.schedule()).append("` | ")
                            .append(job.enabled() ? "enabled" : "disabled")
                            .append(" | last run: ")
                            .append(job.lastRun() != null ? job.lastRun().format(CRON_FMT) : "never");
                    if (job.builtIn()) {
                        sb.append(" | built-in");
                    }
                    sb.append("\n");
                }
                sender.sendMessage(sb.toString());
            }
            case "enable" -> {
                if (parts.length < 3) {
                    sender.sendMessage("Usage: /cron enable <name>");
                    return;
                }
                cronService.enableJob(parts[2]);
                sender.sendMessage("Enabled cron job '%s'.".formatted(parts[2]));
            }
            case "disable" -> {
                if (parts.length < 3) {
                    sender.sendMessage("Usage: /cron disable <name>");
                    return;
                }
                cronService.disableJob(parts[2]);
                sender.sendMessage("Disabled cron job '%s'.".formatted(parts[2]));
            }
            case "edit" -> {
                if (parts.length < 4) {
                    sender.sendMessage("Usage: /cron edit <name> schedule <expr>");
                    return;
                }
                String jobName = parts[2];
                String[] editParts = parts[3].split("\\s+", 2);
                String field = editParts[0].toLowerCase();
                if (!"schedule".equals(field) || editParts.length < 2) {
                    sender.sendMessage("Usage: /cron edit <name> schedule <expr>");
                    return;
                }
                String newSchedule = editParts[1];
                CronJob existing = cronService.findJob(jobName);
                if (existing == null) {
                    sender.sendMessage("Cron job '%s' not found.".formatted(jobName));
                    return;
                }
                cronService.rescheduleJob(jobName, newSchedule);
                sender.sendMessage("Updated schedule for '%s' to `%s`.".formatted(jobName, newSchedule));
            }
            default -> sender.sendMessage(
                    "Unknown cron subcommand: " + subcommand
                            + "\nUsage: /cron list | enable | disable | edit <name> schedule <expr>");
        }
    }

    private void handleConfirm(String[] parts) {
        if (parts.length < 3) {
            sender.sendMessage("Usage: /confirm <id> yes|no");
            return;
        }
        String approvalId = parts[1];
        boolean approved = "yes".equalsIgnoreCase(parts[2]);
        boolean resolved = approvalGate.resolve(approvalId, approved);
        if (!resolved) {
            sender.sendMessage("No pending approval found for ID: " + approvalId);
        }
    }

    private void handleSave(String fullText) {
        String optionalName = extractSaveName(fullText);
        List<Message> history = chatMemory.get(AgentService.DEFAULT_CONVERSATION_ID);
        if (history == null || history.isEmpty()) {
            sender.sendMessage("Nothing to save — the current conversation is empty.");
            return;
        }

        String agentPrompt = buildSavePrompt(optionalName);
        log.info("Dispatching /save command (name='{}', history={} msgs)",
                optionalName == null ? "" : optionalName, history.size());

        sender.sendTypingAction();
        try {
            sender.sendStreamingMessage(
                    agentService.streamChat(agentPrompt, AgentService.DEFAULT_CONVERSATION_ID));
        } catch (Exception e) {
            log.error("/save failed: {}", e.getMessage(), e);
            sender.sendMessage("Sorry, something went wrong saving the conversation. Check the logs.");
        }
    }

    static String extractSaveName(String fullText) {
        if (fullText == null) {
            return null;
        }
        String trimmed = fullText.strip();
        if (!trimmed.toLowerCase().startsWith("/save")) {
            return null;
        }
        String rest = trimmed.substring("/save".length()).strip();
        return rest.isEmpty() ? null : rest;
    }

    static String buildSavePrompt(String optionalName) {
        StringBuilder sb = new StringBuilder()
                .append("The user ran `/save` and wants this conversation filed into long-term ")
                .append("memory as a wiki note. Use the `wiki-ingest` skill, treating the ")
                .append("current conversation as the source material (not a URL or file). ")
                .append("Extract the concepts and entities that came up, create the appropriate ")
                .append("`sources/`, `concepts/`, and `entities/` pages, and update the grouped ")
                .append("`MEMORY.md` index. ");
        if (optionalName != null && !optionalName.isBlank()) {
            sb.append("Use `")
                    .append(optionalName.replaceAll("[^A-Za-z0-9 _-]", ""))
                    .append("` as the source title / slug hint. ");
        } else {
            sb.append("Pick a short, descriptive title and matching slug yourself. ");
        }
        sb.append("When you're done, report back a 1–3 bullet summary of what was created or ")
                .append("updated (source page, new concepts, new entities). The advisor layer ")
                .append("automatically records each memory mutation in `log.md`, so you don't ")
                .append("need to write log entries yourself.");
        return sb.toString();
    }

    private void handleModelSwitch(String provider, String model) {
        try {
            modelSwitcher.switchModel(provider, model);
            sender.sendMessage("Switched to *" + provider + "/" + model
                    + "*. Next message will use the new model.");
            log.info("Model switched to {}/{} via /model command", provider, model);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("Error: " + e.getMessage());
        }
    }

    private void handleModelStatus() {
        UsageTracker.UsageSummary daily = usageTracker.getDailyUsage();
        List<UsageTracker.AgentUsage> breakdown = usageTracker.getDailyUsageByAgent();
        BigDecimal cost = usageTracker.estimateDailyCost();

        var sb = new StringBuilder();
        sb.append("*Model Status*\n\n");
        sb.append("Current model: ").append(modelSwitcher.getActiveProvider())
                .append("/").append(modelSwitcher.getActiveModel()).append("\n");
        sb.append("Available providers: ").append(
                String.join(", ", modelSwitcher.getAvailableProviders())).append("\n");
        sb.append("Tokens today: ").append(formatTokens(daily.tokensIn())).append(" in / ")
                .append(formatTokens(daily.tokensOut())).append(" out\n");
        sb.append("Estimated cost today: $").append(cost.toPlainString()).append("\n");

        if (!breakdown.isEmpty()) {
            sb.append("\n*Per-agent breakdown:*\n");
            for (UsageTracker.AgentUsage usage : breakdown) {
                sb.append("  ").append(usage.agent())
                        .append(" (").append(usage.provider()).append("/").append(usage.model()).append("): ")
                        .append(formatTokens(usage.tokensIn())).append(" in / ")
                        .append(formatTokens(usage.tokensOut())).append(" out\n");
            }
        }

        sender.sendMessage(sb.toString());
    }

    private String formatTokens(long tokens) {
        if (tokens >= 1_000_000) {
            return "%.1fM".formatted(tokens / 1_000_000.0);
        }
        if (tokens >= 1_000) {
            return "%.1fK".formatted(tokens / 1_000.0);
        }
        return String.valueOf(tokens);
    }

    static String formatUptime(long millis) {
        Duration d = Duration.ofMillis(millis);
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        if (days > 0) {
            return "%dd %dh %dm".formatted(days, hours, minutes);
        }
        if (hours > 0) {
            return "%dh %dm".formatted(hours, minutes);
        }
        return "%dm".formatted(minutes);
    }
}
