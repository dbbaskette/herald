package com.herald.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.nio.file.Files;
import java.nio.file.Path;
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
    private volatile ToolCallback delegate;
    private volatile List<SkillsTool.Skill> currentSkills;

    public ReloadableSkillsTool(String skillsDirectory) {
        if (skillsDirectory.startsWith("~")) {
            skillsDirectory = System.getProperty("user.home") + skillsDirectory.substring(1);
        }
        this.skillsDirectory = skillsDirectory;
        reload();
    }

    /**
     * Reload skills from the configured directory and rebuild the inner ToolCallback.
     *
     * @return the number of skills loaded
     */
    public int reload() {
        Path skillsPath = Path.of(skillsDirectory);
        if (!Files.isDirectory(skillsPath)) {
            this.currentSkills = List.of();
            this.delegate = null;
            log.info("Skills directory {} does not exist; 0 skills loaded", skillsDirectory);
            return 0;
        }
        List<SkillsTool.Skill> skills = Skills.loadDirectory(skillsDirectory);
        this.currentSkills = skills;
        if (skills.isEmpty()) {
            this.delegate = null;
            log.info("No skills found in {}", skillsDirectory);
            return 0;
        }
        this.delegate = SkillsTool.builder()
                .addSkillsDirectory(skillsDirectory)
                .build();
        log.info("Loaded {} skill(s) from {}", skills.size(), skillsDirectory);
        return skills.size();
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
        if (delegate != null) {
            return delegate.call(toolInput);
        }
        return "No skills are currently loaded.";
    }
}
