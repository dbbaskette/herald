package com.herald.agent;

import com.herald.agent.profile.AgentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.Ordered;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Standalone factory that builds a {@link ChatClient} from an {@link AgentProfile}
 * and system prompt, without requiring Spring DI. Suitable for ephemeral and
 * subagent use cases where the full {@link HeraldAgentConfig} wiring is not needed.
 */
public final class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);
    private static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a z");

    private AgentFactory() {}

    /**
     * Build a {@link ChatClient} from the given profile, system prompt, and chat model.
     * Uses a default {@link ToolCategoryRegistry} with only stateless tools.
     *
     * @param profile      the agent profile (parsed from agents.md frontmatter)
     * @param systemPrompt the system prompt text (body of agents.md)
     * @param chatModel    the Spring AI ChatModel to use
     * @return a fully configured ChatClient
     */
    public static ChatClient fromProfile(AgentProfile profile, String systemPrompt, ChatModel chatModel) {
        return fromProfile(profile, systemPrompt, chatModel, new ToolCategoryRegistry());
    }

    /**
     * Build a {@link ChatClient} from the given profile, system prompt, chat model,
     * and a pre-configured {@link ToolCategoryRegistry}.
     *
     * @param profile      the agent profile (parsed from agents.md frontmatter)
     * @param systemPrompt the system prompt text (body of agents.md)
     * @param chatModel    the Spring AI ChatModel to use
     * @param registry     the tool category registry to resolve tool names
     * @return a fully configured ChatClient
     */
    public static ChatClient fromProfile(AgentProfile profile, String systemPrompt,
                                          ChatModel chatModel, ToolCategoryRegistry registry) {
        List<Advisor> advisors = buildAdvisors(profile);
        List<Object> tools = registry.resolve(profile.tools());

        var builder = ChatClient.builder(chatModel)
                .defaultSystem(applyTaskManagementGuidance(profile, systemPrompt))
                .defaultAdvisors(advisors);

        if (!tools.isEmpty()) {
            builder.defaultTools(tools.toArray());
        }

        return builder.build();
    }

    /**
     * Build a {@link ChatClient} resolving the provider from the profile.
     *
     * @param profile          agent configuration
     * @param systemPrompt     markdown body
     * @param availableModels  map of provider name to ChatModel
     * @param registry         tool category registry
     * @param providerOverride CLI --provider override (nullable)
     * @param modelOverride    CLI --model override (nullable)
     * @return a fully configured ChatClient
     */
    public static ChatClient fromProfile(AgentProfile profile, String systemPrompt,
                                          Map<String, ChatModel> availableModels,
                                          ToolCategoryRegistry registry,
                                          String providerOverride, String modelOverride) {
        // Resolve provider: CLI override > profile > default "anthropic"
        String provider = providerOverride != null ? providerOverride
                : profile.provider() != null ? profile.provider()
                : "anthropic";

        ChatModel chatModel = availableModels.get(provider);
        if (chatModel == null) {
            throw new IllegalArgumentException(
                    "Provider '" + provider + "' not available. "
                    + "Available: " + availableModels.keySet() + ". "
                    + "Check that the corresponding API key is set.");
        }

        // Model override: resolve via ChatOptions if provided
        String model = modelOverride != null ? modelOverride
                : profile.model() != null ? profile.model()
                : null;

        List<Advisor> advisors = buildAdvisors(profile);
        List<Object> tools = registry.resolve(profile.tools());

        var builder = ChatClient.builder(chatModel)
                .defaultSystem(applyTaskManagementGuidance(profile, systemPrompt))
                .defaultAdvisors(advisors);

        if (model != null) {
            builder.defaultOptions(ModelSwitcher.chatOptionsForModel(chatModel, model, List.of()));
        }

        if (!tools.isEmpty()) {
            builder.defaultTools(tools.toArray());
        }

        return builder.build();
    }

    /**
     * Prepend the shared task-management / tool-use guidance to the agents.md body
     * unless the profile explicitly opts out via {@code task_management: off}.
     * Prepending (rather than appending) keeps the guidance in front of any
     * agent-specific persona or role statements, so task-decomposition discipline
     * is established before the agent reads its specific instructions.
     */
    static String applyTaskManagementGuidance(AgentProfile profile, String systemPrompt) {
        if (!profile.taskManagement()) {
            return systemPrompt;
        }
        String guidance = TaskManagementGuidance.load();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return guidance;
        }
        return guidance + "\n\n" + systemPrompt;
    }

    private static List<Advisor> buildAdvisors(AgentProfile profile) {
        List<Advisor> advisors = new ArrayList<>();

        // Always include date/time injection
        advisors.add(new DateTimePromptAdvisor(DEFAULT_TIMEZONE, DATETIME_FORMAT));

        // Include CONTEXT.md advisor when a context file is configured
        if (profile.contextFile() != null && !profile.contextFile().isBlank()) {
            advisors.add(new ContextMdAdvisor(Path.of(profile.contextFile())));
        }

        // ToolCallAdvisor must be present for tool use; ordered just before ChatModelCallAdvisor
        advisors.add(ToolCallAdvisor.builder()
                .advisorOrder(Ordered.LOWEST_PRECEDENCE - 1)
                .build());

        return advisors;
    }

}
