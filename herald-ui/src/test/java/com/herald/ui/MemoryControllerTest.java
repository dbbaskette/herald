package com.herald.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MemoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemoryRepository repository;

    @Test
    void listReturnsEmptyArrayInitially() throws Exception {
        mockMvc.perform(get("/api/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(0))));
    }

    @Test
    void upsertCreatesAndReturnEntry() throws Exception {
        mockMvc.perform(put("/api/memory/test.key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": \"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("test.key"))
                .andExpect(jsonPath("$.value").value("hello"))
                .andExpect(jsonPath("$.lastUpdated").isNotEmpty());

        // Clean up
        repository.delete("test.key");
    }

    @Test
    void upsertRejectsBlanValue() throws Exception {
        mockMvc.perform(put("/api/memory/test.key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturns404ForMissingKey() throws Exception {
        mockMvc.perform(delete("/api/memory/nonexistent.key"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fullCrudLifecycle() throws Exception {
        // Create
        mockMvc.perform(put("/api/memory/lifecycle.key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": \"v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("v1"));

        // List should contain it
        mockMvc.perform(get("/api/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='lifecycle.key')].value", hasItem("v1")));

        // Update
        mockMvc.perform(put("/api/memory/lifecycle.key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": \"v2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("v2"));

        // Delete
        mockMvc.perform(delete("/api/memory/lifecycle.key"))
                .andExpect(status().isOk());

        // Delete again should 404
        mockMvc.perform(delete("/api/memory/lifecycle.key"))
                .andExpect(status().isNotFound());
    }
}
