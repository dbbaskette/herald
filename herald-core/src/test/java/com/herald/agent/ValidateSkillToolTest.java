package com.herald.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidateSkillToolTest {

    private final ValidateSkillTool tool = new ValidateSkillTool("/tmp/skills");

    @Test
    void validSkillReturnsOk() {
        String content = """
                ---
                name: my-skill
                description: A test skill
                ---
                # My Skill
                Do the thing.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).startsWith("OK");
        assertThat(result).contains("my-skill");
        assertThat(result).contains("/tmp/skills/my-skill/SKILL.md");
    }

    @Test
    void validSkillWithAllOptionalFields() {
        String content = """
                ---
                name: full-skill
                description: A fully specified skill
                allowed-tools: shell, web
                model: claude-sonnet-4-5
                requires-approval: true
                ---
                # Full Skill
                Instructions here.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).startsWith("OK");
    }

    @Test
    void missingFrontmatterDelimiter() {
        String content = "# Just Markdown\nNo frontmatter.";
        String result = tool.validateSkill(content);
        assertThat(result).contains("must start with");
    }

    @Test
    void missingClosingDelimiter() {
        String content = "---\nname: broken\n# No closing delimiter";
        String result = tool.validateSkill(content);
        assertThat(result).contains("closing");
    }

    @Test
    void missingNameField() {
        String content = """
                ---
                description: No name
                ---
                # Skill
                Body.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).contains("name");
    }

    @Test
    void invalidNameFormat() {
        String content = """
                ---
                name: My Skill With Spaces
                description: Invalid name
                ---
                # Skill
                Body.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).contains("name");
        assertThat(result).contains("lowercase");
    }

    @Test
    void missingDescription() {
        String content = """
                ---
                name: no-desc
                ---
                # Skill
                Body.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).contains("description");
    }

    @Test
    void emptyMarkdownBody() {
        String content = """
                ---
                name: empty-body
                description: Has no body
                ---
                """;
        String result = tool.validateSkill(content);
        assertThat(result).contains("body");
    }

    @Test
    void nullContentReturnsError() {
        String result = tool.validateSkill(null);
        assertThat(result).contains("empty");
    }

    @Test
    void blankContentReturnsError() {
        String result = tool.validateSkill("   ");
        assertThat(result).contains("empty");
    }

    @Test
    void invalidYamlReturnsError() {
        String content = """
                ---
                name: [invalid yaml
                description: broken
                ---
                # Skill
                Body.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).containsIgnoringCase("YAML");
    }

    @Test
    void allowedToolsAsListIsValid() {
        String content = """
                ---
                name: list-tools
                description: Uses list syntax
                allowed-tools:
                  - shell
                  - web
                ---
                # Skill
                Body.
                """;
        String result = tool.validateSkill(content);
        assertThat(result).startsWith("OK");
    }
}
