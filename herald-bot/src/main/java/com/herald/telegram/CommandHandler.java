package com.herald.telegram;

import com.herald.agent.AgentService;
import com.herald.agent.ModelSwitcher;
import com.herald.agent.UsageTracker;
import com.herald.cron.CronJob;
import com.herald.cron.CronService;
import com.herald.memory.MemoryTools;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Component
class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private static final DateTimeFormatter CRON_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MemoryTools memoryTools;
    private final CronService cronService;
    private final ChatMemory chatMemory;
    private final TelegramSender sender;
    private final UsageTracker usageTracker;
    private final ModelSwitcher modelSwitcher;
    private final ReloadableSkillsTool reloadableSkillsTool;
    private final int activeToolsCount;
    private final int maxContextTokens;
    private final AtomicBoolean pendingMemoryClear = new AtomicBoolean(false);

    CommandHandler(MemoryTools memoryTools, CronService cronService, ChatMemory chatMemory,
                   TelegramSender sender, UsageTracker usageTracker, ModelSwitcher modelSwitcher,
                   @Qualifier("activeToolNames") List<String> activeToolNames,
                   ReloadableSkillsTool reloadableSkillsTool,
                   @Value("${herald.agent.max-context-tokens:200000}") int maxContextTokens) {
        this.memoryTools = memoryTools;
        this.cronService = cronService;
        this.chatMemory = chatMemory;
        this.sender = sender;
        this.usageTracker = usageTracker;
        this.modelSwitcher = modelSwitcher;
        this.reloadableSkillsTool = reloadableSkillsTool;
        this.activeToolsCount = activeToolNames.size();
        this.maxContextTokens = maxContextTokens;
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
                /memory list — Display all stored memory entries
                /memory set <key> <value> — Store a memory entry
                /memory clear — Clear all memory entries
                /model status — Show current model and daily token usage
                /model <provider> <model> — Switch to a different model
                /skills list — List all loaded skills
                /skills reload — Reload skills from disk
                /cron list — List all scheduled cron jobs
                /cron enable <name> — Enable a cron job
                /cron disable <name> — Disable a cron job
                """;
        sender.sendMessage(help);
    }

    private void handleStatus() {
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        String uptime = formatUptime(uptimeMillis);
        int memoryCount = memoryTools.count();

        String modelName = modelSwitcher.getActiveProvider() + "/" + modelSwitcher.getActiveModel();
        String status = """
                *System Status*

                Uptime: %s
                Model: %s
                Memory entries: %d
                Active tools: %d
                """.formatted(uptime, modelName, memoryCount, activeToolsCount);
        sender.sendMessage(status);
    }

    private void handleReset() {
        chatMemory.clear(AgentService.DEFAULT_CONVERSATION_ID);
        sender.sendMessage("Conversation history cleared. Memory entries are preserved.");
        log.info("Conversation history cleared via /reset command");
    }

    private void handleDebug() {
        int memoryCount = memoryTools.count();
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
                Memory entries: %d
                Active tools: %d
                Model: %s
                Available providers: %s
                """.formatted(contextMessages,
                formatTokens(estimatedTokens), usagePercent,
                formatTokens(maxContextTokens), formatTokens(ceiling),
                memoryCount, activeToolsCount, modelName,
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
        if (parts.length < 2) {
            sender.sendMessage("Usage: /memory list | /memory set <key> <value> | /memory clear");
            return;
        }

        String subcommand = parts[1].toLowerCase();
        switch (subcommand) {
            case "list" -> {
                String result = memoryTools.memory_list();
                sender.sendMessage(result);
            }
            case "clear" -> {
                if (parts.length >= 3 && "confirm".equalsIgnoreCase(parts[2]) && pendingMemoryClear.get()) {
                    pendingMemoryClear.set(false);
                    memoryTools.clearAll();
                    sender.sendMessage("All memory entries cleared.");
                    log.info("Memory cleared via /memory clear confirm command");
                } else if (parts.length >= 3 && "confirm".equalsIgnoreCase(parts[2])) {
                    sender.sendMessage("No pending confirmation. Use `/memory clear` first.");
                } else if (pendingMemoryClear.compareAndSet(false, true)) {
                    sender.sendMessage("Are you sure? Reply `/memory clear confirm` to proceed.");
                } else {
                    sender.sendMessage("Confirmation already pending. Reply `/memory clear confirm` to proceed.");
                }
            }
            case "set" -> {
                if (parts.length < 4) {
                    sender.sendMessage("Usage: /memory set <key> <value>");
                    return;
                }
                String key = parts[2];
                String value = parts[3];
                String result = memoryTools.memory_set(key, value);
                sender.sendMessage(result);
            }
            default -> sender.sendMessage(
                    "Unknown memory subcommand: " + subcommand
                            + "\nUsage: /memory list | /memory set <key> <value> | /memory clear");
        }
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
            sender.sendMessage("Usage: /cron list | /cron enable <name> | /cron disable <name>");
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
                            .append(job.lastRun() != null ? job.lastRun().format(CRON_FMT) : "never")
                            .append("\n");
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
            default -> sender.sendMessage(
                    "Unknown cron subcommand: " + subcommand
                            + "\nUsage: /cron list | /cron enable <name> | /cron disable <name>");
        }
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
