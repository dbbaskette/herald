package com.herald.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.herald.ui.config.HeraldUiConfig;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SkillsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HeraldUiConfig config;

    private Path skillsDir;
    private Path bundledSkillsDir;

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = resolvePath(config.skillsPath());
        Files.createDirectories(skillsDir);
        bundledSkillsDir = resolvePath(config.bundledSkillsPath());
        Files.createDirectories(bundledSkillsDir);
    }

    @AfterEach
    void cleanup() throws IOException {
        deleteDir(skillsDir);
        deleteDir(bundledSkillsDir);
    }

    private static void deleteDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    }
                    catch (IOException e) {
                        // ignore
                    }
                });
            }
        }
    }

    private static Path resolvePath(String path) {
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return Path.of(path);
    }

    @Test
    void listReturnsEmptyArrayWhenNoSkills() throws Exception {
        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listReturnsSkillsWithMetadata() throws Exception {
        createSkill("greeting", """
                ---
                name: greeting
                description: A greeting skill
                ---
                # Greeting
                """);
        createSkill("farewell", """
                ---
                name: farewell
                description: A farewell skill
                ---
                # Farewell
                """);

        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("farewell"))
                .andExpect(jsonPath("$[0].description").value("A farewell skill"))
                .andExpect(jsonPath("$[0].source").value("local"))
                .andExpect(jsonPath("$[0].readOnly").value(false))
                .andExpect(jsonPath("$[1].name").value("greeting"))
                .andExpect(jsonPath("$[1].description").value("A greeting skill"))
                .andExpect(jsonPath("$[1].source").value("local"))
                .andExpect(jsonPath("$[1].readOnly").value(false));
    }

    @Test
    void getReturnsRawMarkdownAsPlainText() throws Exception {
        String content = """
                ---
                name: test-skill
                description: A test skill
                ---
                # Test Skill
                Some content""";
        createSkill("test-skill", content);

        mockMvc.perform(get("/api/skills/test-skill"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(content));
    }

    @Test
    void getReturns404ForMissingSkill() throws Exception {
        mockMvc.perform(get("/api/skills/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReturns400ForInvalidName() throws Exception {
        mockMvc.perform(get("/api/skills/bad.name"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putUpdatesExistingSkill() throws Exception {
        createSkill("my-skill", "---\nname: my-skill\ndescription: A skill\n---\n# Original\n");

        String updated = "---\nname: my-skill\ndescription: Updated\n---\n# Updated\n";
        mockMvc.perform(put("/api/skills/my-skill")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(updated))
                .andExpect(status().isOk());

        Path skillFile = skillsDir.resolve("my-skill").resolve("SKILL.md");
        assert Files.readString(skillFile).equals(updated);
    }

    @Test
    void putReturns404ForNonexistentSkill() throws Exception {
        mockMvc.perform(put("/api/skills/no-such-skill")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("content"))
                .andExpect(status().isNotFound());
    }

    @Test
    void postCreatesNewSkillWithTemplate() throws Exception {
        mockMvc.perform(post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"new-skill\"}"))
                .andExpect(status().isCreated());

        Path skillFile = skillsDir.resolve("new-skill").resolve("SKILL.md");
        assert Files.exists(skillFile);
        String content = Files.readString(skillFile);
        assert content.contains("name: new-skill");
        assert content.contains("description:");
    }

    @Test
    void postReturns409IfSkillExists() throws Exception {
        createSkill("existing", "# Existing");

        mockMvc.perform(post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"existing\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteRemovesSkillDirectory() throws Exception {
        createSkill("to-delete", "# Delete me");

        mockMvc.perform(delete("/api/skills/to-delete"))
                .andExpect(status().isNoContent());

        assert !Files.exists(skillsDir.resolve("to-delete"));
    }

    @Test
    void deleteReturns404ForMissingSkill() throws Exception {
        mockMvc.perform(delete("/api/skills/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pathTraversalIsPrevented() throws Exception {
        mockMvc.perform(put("/api/skills/bad..name")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("malicious"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"bad/name\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void parseFrontmatterExtractsFields() {
        String content = """
                ---
                name: test
                description: >
                  A multi-line description
                  that spans two lines.
                ---
                # Body
                """;
        var fm = SkillsController.parseFrontmatter(content);
        assert "test".equals(fm.get("name"));
        assert fm.get("description").startsWith("A multi-line");
    }

    @Test
    void listIncludesBundledSkills() throws Exception {
        createBundledSkill("built-in", """
                ---
                name: built-in
                description: A bundled skill
                ---
                # Built-in
                """);

        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("built-in"))
                .andExpect(jsonPath("$[0].source").value("bundled"))
                .andExpect(jsonPath("$[0].readOnly").value(true));
    }

    @Test
    void deleteReturns403ForBundledSkill() throws Exception {
        createBundledSkill("protected", "# Protected");

        mockMvc.perform(delete("/api/skills/protected"))
                .andExpect(status().isForbidden());
    }

    @Test
    void putReturns403ForBundledSkill() throws Exception {
        createBundledSkill("protected", "# Protected");

        mockMvc.perform(put("/api/skills/protected")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("overwrite attempt"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getReturnsBundledSkillContent() throws Exception {
        String content = "---\nname: bundled-test\n---\n# Bundled";
        createBundledSkill("bundled-test", content);

        mockMvc.perform(get("/api/skills/bundled-test"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(content));
    }

    private void createSkill(String name, String content) throws IOException {
        Path dir = skillsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content);
    }

    private void createBundledSkill(String name, String content) throws IOException {
        Path dir = bundledSkillsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content);
    }
}
