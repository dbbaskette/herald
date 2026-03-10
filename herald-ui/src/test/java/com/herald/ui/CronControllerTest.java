package com.herald.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CronControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM cron_jobs");
        jdbcTemplate.update("DELETE FROM commands");
    }

    @Test
    void listReturnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/cron"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listReturnsCronJobs() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO cron_jobs (name, schedule, prompt, enabled) VALUES (?, ?, ?, 1)",
                "daily-report", "0 9 * * *", "Generate a daily report");

        mockMvc.perform(get("/api/cron"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("daily-report"))
                .andExpect(jsonPath("$[0].schedule").value("0 9 * * *"));
    }

    @Test
    void updateReturns200WithUpdatedJob() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO cron_jobs (name, schedule, prompt, enabled) VALUES (?, ?, ?, 1)",
                "test-job", "0 9 * * *", "old prompt");
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM cron_jobs WHERE name = ?", Long.class, "test-job");

        mockMvc.perform(put("/api/cron/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schedule\": \"0 10 * * *\", \"prompt\": \"new prompt\", \"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule").value("0 10 * * *"))
                .andExpect(jsonPath("$.prompt").value("new prompt"))
                .andExpect(jsonPath("$.enabled").value(0));
    }

    @Test
    void updateReturns404ForMissingJob() throws Exception {
        mockMvc.perform(put("/api/cron/99999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schedule\": \"0 10 * * *\", \"prompt\": \"test\", \"enabled\": true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void runReturns202WithCommand() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO cron_jobs (name, schedule, prompt, enabled) VALUES (?, ?, ?, 1)",
                "run-job", "0 9 * * *", "do something");
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM cron_jobs WHERE name = ?", Long.class, "run-job");

        mockMvc.perform(post("/api/cron/" + id + "/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("RUN_CRON"))
                .andExpect(jsonPath("$.payload").value(String.valueOf(id)))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void runReturns404ForMissingJob() throws Exception {
        mockMvc.perform(post("/api/cron/99999/run"))
                .andExpect(status().isNotFound());
    }
}
