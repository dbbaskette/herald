package com.herald.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ValidateSkillTool {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    private final String skillsDirectory;

    public ValidateSkillTool(
            @Value("${herald.agent.skills-directory:skills}") String skillsDirectory) {
        if (skillsDirectory.startsWith("~")) {
            skillsDirectory = System.getProperty("user.home") + skillsDirectory.substring(1);
        }
        this.skillsDirectory = skillsDirectory;
    }

    @Tool(description = "Validate a SKILL.md file before writing it to the skills directory. "
            + "Pass the full content of the SKILL.md you intend to write. "
            + "Returns OK if valid, or a list of errors to fix.")
    public String validateSkill(
            @ToolParam(description = "The full content of the SKILL.md file to validate")
            String content) {

        if (content == null || content.isBlank()) {
            return "Validation failed:\n1. Content is empty. "
                    + "Provide SKILL.md content with YAML frontmatter and a Markdown body.";
        }

        List<String> errors = new ArrayList<>();
        String trimmed = content.strip();

        if (!trimmed.startsWith("---")) {
            errors.add("Content must start with '---' (YAML frontmatter delimiter).");
            return formatErrors(errors);
        }

        int closingIdx = trimmed.indexOf("---", 3);
        if (closingIdx < 0) {
            errors.add("Missing closing '---' delimiter for YAML frontmatter.");
            return formatErrors(errors);
        }

        String yamlBlock = trimmed.substring(3, closingIdx).strip();
        String markdownBody = trimmed.substring(closingIdx + 3).strip();

        Map<String, Object> frontmatter;
        try {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(yamlBlock);
            if (!(parsed instanceof Map)) {
                errors.add("YAML frontmatter must be a mapping (key: value pairs).");
                return formatErrors(errors);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fm = (Map<String, Object>) parsed;
            frontmatter = fm;
        } catch (Exception e) {
            errors.add("Invalid YAML in frontmatter: " + e.getMessage());
            return formatErrors(errors);
        }

        Object nameVal = frontmatter.get("name");
        if (nameVal == null || nameVal.toString().isBlank()) {
            errors.add("Missing required field: name");
        } else if (!NAME_PATTERN.matcher(nameVal.toString()).matches()) {
            errors.add("name must be lowercase letters, numbers, and hyphens only "
                    + "(e.g., 'my-skill'). Got: '" + nameVal + "'");
        }

        Object descVal = frontmatter.get("description");
        if (descVal == null || descVal.toString().isBlank()) {
            errors.add("Missing required field: description");
        }

        if (frontmatter.containsKey("allowed-tools")) {
            try {
                List<String> tools = ReloadableSkillsTool.parseStringOrList(
                        frontmatter.get("allowed-tools"));
                if (tools.isEmpty()) {
                    errors.add("allowed-tools is present but empty. "
                            + "Remove it or provide tool names.");
                }
            } catch (Exception e) {
                errors.add("allowed-tools could not be parsed: " + e.getMessage());
            }
        }

        if (frontmatter.containsKey("model")) {
            Object modelVal = frontmatter.get("model");
            if (modelVal == null || modelVal.toString().isBlank()) {
                errors.add("model field is present but empty.");
            }
        }

        if (frontmatter.containsKey("requires-approval")) {
            Object approvalVal = frontmatter.get("requires-approval");
            if (!(approvalVal instanceof Boolean)
                    && !"true".equalsIgnoreCase(String.valueOf(approvalVal))
                    && !"false".equalsIgnoreCase(String.valueOf(approvalVal))) {
                errors.add("requires-approval must be true or false. Got: '"
                        + approvalVal + "'");
            }
        }

        if (markdownBody.isEmpty()) {
            errors.add("Markdown body after frontmatter is empty. "
                    + "Add skill instructions.");
        }

        if (!errors.isEmpty()) {
            return formatErrors(errors);
        }

        String name = nameVal.toString();
        return "OK — skill '" + name + "' is valid and ready to write to: "
                + skillsDirectory + "/" + name + "/SKILL.md";
    }

    private static String formatErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder("Validation failed:\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append(i + 1).append(". ").append(errors.get(i)).append("\n");
        }
        return sb.toString().strip();
    }
}
