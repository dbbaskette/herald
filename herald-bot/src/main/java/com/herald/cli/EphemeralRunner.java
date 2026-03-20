package com.herald.cli;

import com.herald.agent.AgentFactory;
import com.herald.agent.profile.AgentProfileParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 */
@Component
public class EphemeralRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EphemeralRunner.class);

    private final ChatModel chatModel;
    private final PrintStream out;
    private final PrintStream err;

    public EphemeralRunner(ChatModel chatModel) {
        this(chatModel, System.out, System.err);
    }

    EphemeralRunner(ChatModel chatModel, PrintStream out) {
        this(chatModel, out, System.err);
    }

    EphemeralRunner(ChatModel chatModel, PrintStream out, PrintStream err) {
        this.chatModel = chatModel;
        this.out = out;
        this.err = err;
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

        try {
            var parsed = AgentProfileParser.parseFile(agentFile);
            ChatClient client = AgentFactory.fromProfile(
                    parsed.profile(), parsed.systemPrompt(), chatModel);

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
