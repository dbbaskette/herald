package com.herald.telegram;

import com.herald.agent.AgentService;
import com.herald.agent.ModelSwitcher;
import com.herald.agent.UsageTracker;
import com.herald.memory.MemoryTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private final MemoryTools memoryTools;
    private final ChatMemory chatMemory;
    private final TelegramSender sender;
    private final UsageTracker usageTracker;
    private final ModelSwitcher modelSwitcher;
    private final String skillsDirectory;
    private final int activeToolsCount;
    private final AtomicBoolean pendingMemoryClear = new AtomicBoolean(false);

    CommandHandler(MemoryTools memoryTools, ChatMemory chatMemory, TelegramSender sender,
                   UsageTracker usageTracker, ModelSwitcher modelSwitcher,
                   @Qualifier("activeToolNames") List<String> activeToolNames,
                   @Qualifier("skillsDirectory") String skillsDirectory) {
        this.memoryTools = memoryTools;
        this.chatMemory = chatMemory;
        this.sender = sender;
        this.usageTracker = usageTracker;
        this.modelSwitcher = modelSwitcher;
        this.skillsDirectory = skillsDirectory;
        this.activeToolsCount = activeToolNames.size();
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
        int contextMessages = chatMemory.get(AgentService.DEFAULT_CONVERSATION_ID).size();

        String modelName = modelSwitcher.getActiveProvider() + "/" + modelSwitcher.getActiveModel();
        String debug = """
                *Debug Info*

                Context messages: %d
                Memory entries: %d
                Active tools: %d
                Model: %s
                Available providers: %s
                """.formatted(contextMessages, memoryCount, activeToolsCount, modelName,
                String.join(", ", modelSwitcher.getAvailableProviders()));
        sender.sendMessage(debug);
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
                List<SkillsTool.Skill> skills = Skills.loadDirectory(skillsDirectory);
                if (skills.isEmpty()) {
                    sender.sendMessage("No skills found in " + skillsDirectory);
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
                List<SkillsTool.Skill> skills = Skills.loadDirectory(skillsDirectory);
                sender.sendMessage("Reloaded %d skill(s) from %s".formatted(skills.size(), skillsDirectory));
                log.info("Skills reloaded from {}: {} skill(s)", skillsDirectory, skills.size());
            }
            default -> sender.sendMessage(
                    "Unknown skills subcommand: " + subcommand
                            + "\nUsage: /skills list | /skills reload");
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
