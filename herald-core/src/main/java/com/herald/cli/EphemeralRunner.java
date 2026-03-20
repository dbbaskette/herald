package com.herald.cli;

import com.herald.agent.AgentFactory;
import com.herald.agent.ToolCategoryRegistry;
import com.herald.agent.profile.AgentProfileParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;

/**
 * CLI entry point for ephemeral (one-shot or REPL) agent execution.
 *
 * <p>When {@code --agents=<path>} is present on the command line, this runner
 * parses the agent profile, builds a ChatClient, and either executes a single
 * prompt ({@code --prompt=<text>}) or enters an interactive REPL loop.
 *
 * <p>When {@code --agents} is absent, this runner does nothing, allowing the
 * persistent Telegram bot mode to operate normally.
 *
 * <p>Supports {@code --provider=<name>} and {@code --model=<id>} CLI overrides
 * to select a specific provider/model at runtime.
 */
@Component
public class EphemeralRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EphemeralRunner.class);

    private final Map<String, ChatModel> availableModels;
    private final PrintStream out;
    private final PrintStream err;

    public EphemeralRunner(
            @Qualifier("anthropicChatModel") ChatModel anthropicChatModel,
            @Qualifier("openaiChatModel") Optional<ChatModel> openaiChatModel,
            @Qualifier("ollamaChatModel") Optional<ChatModel> ollamaChatModel,
            @Qualifier("geminiChatModel") Optional<ChatModel> geminiChatModel) {
        this(buildAvailableModels(anthropicChatModel, openaiChatModel, ollamaChatModel, geminiChatModel),
                System.out, System.err);
    }

    EphemeralRunner(Map<String, ChatModel> availableModels, PrintStream out, PrintStream err) {
        this.availableModels = availableModels;
        this.out = out;
        this.err = err;
    }

    private static Map<String, ChatModel> buildAvailableModels(
            ChatModel anthropicChatModel,
            Optional<ChatModel> openaiChatModel,
            Optional<ChatModel> ollamaChatModel,
            Optional<ChatModel> geminiChatModel) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        models.put("anthropic", anthropicChatModel);
        openaiChatModel.ifPresent(m -> models.put("openai", m));
        ollamaChatModel.ifPresent(m -> models.put("ollama", m));
        geminiChatModel.ifPresent(m -> models.put("gemini", m));
        return models;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("agents")) {
            return; // Not ephemeral mode — let persistent mode run
        }

        String agentsPath = args.getOptionValues("agents").getFirst();
        Path agentFile = Path.of(agentsPath);

        if (!Files.exists(agentFile)) {
            err.println("Error: agents file not found: " + agentsPath);
            return;
        }

        // Parse optional CLI overrides
        String providerOverride = args.containsOption("provider")
                ? args.getOptionValues("provider").getFirst() : null;
        String modelOverride = args.containsOption("model")
                ? args.getOptionValues("model").getFirst() : null;

        try {
            var parsed = AgentProfileParser.parseFile(agentFile);
            ChatClient client = AgentFactory.fromProfile(
                    parsed.profile(), parsed.systemPrompt(),
                    availableModels, new ToolCategoryRegistry(),
                    providerOverride, modelOverride);

            if (args.containsOption("prompt")) {
                String prompt = args.getOptionValues("prompt").getFirst();
                String response = client.prompt(prompt).call().content();
                out.println(response);
            } else {
                // Interactive REPL
                out.println("Herald ephemeral mode — " + parsed.profile().name());
                out.println("Type your prompt (Ctrl+D to exit):");
                try (Scanner scanner = new Scanner(System.in)) {
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine().trim();
                        if (line.isEmpty()) continue;
                        String response = client.prompt(line).call().content();
                        out.println(response);
                        out.println();
                    }
                }
            }
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
            log.error("Ephemeral runner failed", e);
        }
    }
}
