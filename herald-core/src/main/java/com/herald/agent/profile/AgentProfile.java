package com.herald.agent.profile;

import java.util.List;

/**
 * Parsed agent configuration from an {@code agents.md} YAML frontmatter block.
 * This is a superset of the {@code .claude/agents/*.md} subagent format, adding
 * fields for provider selection, memory, context file, and token limits.
 *
 * @param name              agent identifier
 * @param description       human-readable description
 * @param model             model selector (e.g. "sonnet", "opus", "gpt-4o")
 * @param provider          provider name (e.g. "anthropic", "openai", "ollama"); nullable
 * @param tools             list of tool names to enable
 * @param skillsDirectory   path to skills directory; nullable
 * @param subagentsDirectory path to subagents directory; nullable
 * @param memory            whether to enable persistent memory
 * @param contextFile       path to CONTEXT.md file; nullable
 * @param maxTokens         maximum context window tokens; nullable
 * @param taskManagement    whether to prepend the shared task-management / tool-use
 *                          guidance snippet to the system prompt; defaults to true
 */
public record AgentProfile(
        String name,
        String description,
        String model,
        String provider,
        List<String> tools,
        String skillsDirectory,
        String subagentsDirectory,
        boolean memory,
        String contextFile,
        Integer maxTokens,
        boolean taskManagement
) {}
