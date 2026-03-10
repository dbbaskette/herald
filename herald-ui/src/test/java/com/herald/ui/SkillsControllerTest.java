package com.herald.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

    @BeforeEach
    void setUp() throws IOException {
        String path = config.skillsPath();
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        skillsDir = Path.of(path);
        Files.createDirectories(skillsDir);
    }

    @AfterEach
    void cleanup() throws IOException {
        if (Files.exists(skillsDir)) {
            try (var files = Files.list(skillsDir)) {
                files.forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException e) {
                        // ignore
                    }
                });
            }
            Files.deleteIfExists(skillsDir);
        }
    }

    @Test
    void listReturnsEmptyArrayWhenNoSkills() throws Exception {
        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listReturnsSkillNamesWithoutExtension() throws Exception {
        Files.writeString(skillsDir.resolve("greeting.md"), "Hello!");
        Files.writeString(skillsDir.resolve("farewell.md"), "Goodbye!");

        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0]").value("farewell"))
                .andExpect(jsonPath("$[1]").value("greeting"));
    }

    @Test
    void getReturnsSkillContent() throws Exception {
        Files.writeString(skillsDir.resolve("test-skill.md"), "# Test Skill\nSome content");

        mockMvc.perform(get("/api/skills/test-skill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("test-skill"))
                .andExpect(jsonPath("$.content").value("# Test Skill\nSome content"));
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
    void putCreatesOrUpdatesSkill() throws Exception {
        mockMvc.perform(put("/api/skills/my-skill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("my-skill"))
                .andExpect(jsonPath("$.content").value("v1"));

        // Update
        mockMvc.perform(put("/api/skills/my-skill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"v2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("v2"));
    }

    @Test
    void postCreatesNewSkill() throws Exception {
        mockMvc.perform(post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"new-skill\", \"content\": \"hello\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("new-skill"))
                .andExpect(jsonPath("$.content").value("hello"));
    }

    @Test
    void postReturns409IfSkillExists() throws Exception {
        Files.writeString(skillsDir.resolve("existing.md"), "already here");

        mockMvc.perform(post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"existing\", \"content\": \"new content\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteRemovesSkill() throws Exception {
        Files.writeString(skillsDir.resolve("to-delete.md"), "bye");

        mockMvc.perform(delete("/api/skills/to-delete"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/skills/to-delete"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns404ForMissingSkill() throws Exception {
        mockMvc.perform(delete("/api/skills/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pathTraversalIsPrevented() throws Exception {
        mockMvc.perform(put("/api/skills/bad..name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"malicious\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"bad/name\", \"content\": \"malicious\"}"))
                .andExpect(status().isBadRequest());
    }
}
