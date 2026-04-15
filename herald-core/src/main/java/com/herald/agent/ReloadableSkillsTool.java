package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.io.Resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A delegating {@link ToolCallback} wrapper around {@link SkillsTool} that supports
 * reloading skills from disk at runtime. The inner tool callback is rebuilt on each
 * {@link #reload()} call, and the delegate is swapped atomically so that the ChatClient
 * picks up the new skills on the next tool invocation.
 */
public class ReloadableSkillsTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ReloadableSkillsTool.class);

    private final String skillsDirectory;
    private final List<Resource> classpathResources;
    private final ApprovalGate approvalGate;
    private final List<String> skillsRequiringApproval;
    private volatile ToolCallback delegate;
    private volatile List<SkillsTool.Skill> currentSkills;

    public ReloadableSkillsTool(String skillsDirectory) {
        this(skillsDirectory, List.of(), null, List.of());
    }

    public ReloadableSkillsTool(String skillsDirectory, List<Resource> classpathResources) {
        this(skillsDirectory, classpathResources, null, List.of());
    }

    public ReloadableSkillsTool(String skillsDirectory, List<Resource> classpathResources,
                                ApprovalGate approvalGate, List<String> skillsRequiringApproval) {
        if (skillsDirectory.startsWith("~")) {
            skillsDirectory = System.getProperty("user.home") + skillsDirectory.substring(1);
        }
        this.skillsDirectory = skillsDirectory;
        this.classpathResources = classpathResources != null ? List.copyOf(classpathResources) : List.of();
        this.approvalGate = approvalGate;
        this.skillsRequiringApproval = skillsRequiringApproval != null
                ? List.copyOf(skillsRequiringApproval) : List.of();
        reload();
    }

    /**
     * Reload skills from the configured directory and rebuild the inner ToolCallback.
     *
     * @return the number of skills loaded
     */
    public int reload() {
        List<SkillsTool.Skill> allSkills = new ArrayList<>();

        // Load from filesystem directory
        Path skillsPath = Path.of(skillsDirectory);
        if (Files.isDirectory(skillsPath)) {
            allSkills.addAll(Skills.loadDirectory(skillsDirectory));
        } else {
            log.debug("Skills directory {} does not exist", skillsDirectory);
        }

        // Load from classpath resources
        if (!classpathResources.isEmpty()) {
            allSkills.addAll(Skills.loadResources(classpathResources));
        }

        this.currentSkills = Collections.unmodifiableList(allSkills);
        if (allSkills.isEmpty()) {
            this.delegate = null;
            log.info("No skills loaded (directory: {}, classpath resources: {})",
                    skillsDirectory, classpathResources.size());
            return 0;
        }

        var builder = SkillsTool.builder();
        if (Files.isDirectory(skillsPath)) {
            builder.addSkillsDirectory(skillsDirectory);
        }
        if (!classpathResources.isEmpty()) {
            builder.addSkillsResources(classpathResources);
        }
        this.delegate = builder.build();
        log.info("Loaded {} skill(s) (directory: {}, classpath: {})",
                allSkills.size(), skillsDirectory, classpathResources.size());
        return allSkills.size();
    }

    public List<SkillsTool.Skill> getSkills() {
        return currentSkills;
    }

    public String getSkillsDirectory() {
        return skillsDirectory;
    }

    boolean hasSkills() {
        return delegate != null;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        if (delegate != null) {
            return delegate.getToolDefinition();
        }
        // Provide a minimal definition when no skills are loaded
        return ToolDefinition.builder()
                .name("skills")
                .description("No skills currently loaded")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        if (delegate != null) {
            return delegate.getToolMetadata();
        }
        return ToolCallback.super.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        if (delegate == null) {
            return "No skills are currently loaded.";
        }

        String skillName = extractSkillName(toolInput);

        if (skillName != null && requiresApproval(skillName) && approvalGate != null) {
            String approval = approvalGate.requestApproval("Execute skill: " + skillName);
            if (!"APPROVED".equals(approval)) {
                return "DENIED: Skill '" + skillName + "' requires user approval. Status: " + approval;
            }
        }

        String result = delegate.call(toolInput);

        if (skillName != null) {
            List<String> allowedTools = getAllowedTools(skillName);
            if (!allowedTools.isEmpty()) {
                result = "<allowed-tools>\nWhile executing this skill, you may ONLY use these tools: "
                        + String.join(", ", allowedTools)
                        + "\n</allowed-tools>\n\n" + result;
            }
        }
        return result;
    }

    /**
     * Returns the {@code allowed-tools} list from a skill's frontmatter, or an empty list
     * if not specified. Supports both comma-separated strings and YAML list syntax.
     * When present, the agent should restrict tool calls to these tools while executing the skill.
     */
    @SuppressWarnings("unchecked")
    public List<String> getAllowedTools(String skillName) {
        if (currentSkills == null) return List.of();
        return currentSkills.stream()
                .filter(s -> s.name().equals(skillName))
                .findFirst()
                .map(s -> s.frontMatter().get("allowed-tools"))
                .map(ReloadableSkillsTool::parseStringOrList)
                .orElse(List.of());
    }

    /**
     * Parses a frontmatter value that may be a List (YAML list) or a comma-separated String.
     */
    public static List<String> parseStringOrList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(v -> v instanceof String)
                    .map(v -> ((String) v).trim())
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        if (value instanceof String str && !str.isBlank()) {
            return List.of(str.split(",")).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return List.of();
    }

    /**
     * Returns the {@code model} preference from a skill's frontmatter, or null if not specified.
     */
    public String getSkillModel(String skillName) {
        if (currentSkills == null) return null;
        return currentSkills.stream()
                .filter(s -> s.name().equals(skillName))
                .findFirst()
                .map(s -> s.frontMatter().get("model"))
                .filter(v -> v instanceof String)
                .map(v -> (String) v)
                .orElse(null);
    }

    public boolean requiresApproval(String skillName) {
        if (skillsRequiringApproval.contains(skillName)) return true;
        if (currentSkills == null) return false;
        return currentSkills.stream()
                .filter(s -> s.name().equals(skillName))
                .findFirst()
                .map(s -> s.frontMatter().get("requires-approval"))
                .map(v -> Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v)))
                .orElse(false);
    }

    private static String extractSkillName(String toolInput) {
        if (toolInput == null) return null;
        // Input is JSON like {"command":"skill-name"}
        int idx = toolInput.indexOf("\"command\"");
        if (idx < 0) return null;
        int colon = toolInput.indexOf(':', idx);
        if (colon < 0) return null;
        int firstQuote = toolInput.indexOf('"', colon + 1);
        if (firstQuote < 0) return null;
        int lastQuote = toolInput.indexOf('"', firstQuote + 1);
        if (lastQuote < 0) return null;
        return toolInput.substring(firstQuote + 1, lastQuote);
    }
}
