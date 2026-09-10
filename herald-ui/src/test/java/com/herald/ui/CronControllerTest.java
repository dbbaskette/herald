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
        jdbcTemplate.update("DELETE FROM cron_execution");
        jdbcTemplate.update("DELETE FROM settings WHERE key='cron.runtime-timezone'");
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
                .andExpect(jsonPath("$[0].expression").value("0 9 * * *"));
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
                .andExpect(jsonPath("$.expression").value("0 10 * * *"))
                .andExpect(jsonPath("$.promptText").value("new prompt"))
                .andExpect(jsonPath("$.enabled").value(false));
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

    @Test
    void typedRoundtripPreservesDisabledStateAndQueuesSync() throws Exception {
        var result = mockMvc.perform(post("/api/cron-jobs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"advanced\",\"expression\":\"15 0/10 9-17 * * MON,WED,FRI\",\"promptText\":\"hello\",\"enabled\":false}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.timezone").value("America/New_York"))
                .andExpect(jsonPath("$.builtIn").value(false)).andExpect(jsonPath("$.nextRun").isEmpty())
                .andExpect(jsonPath("$.scheduleStatus").value("queued")).andReturn();
        Long id = jdbcTemplate.queryForObject("SELECT id FROM cron_jobs WHERE name='advanced'", Long.class);
        mockMvc.perform(patch("/api/cron/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"promptText\":\"changed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expression").value("15 0/10 9-17 * * MON,WED,FRI"))
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(post("/api/cron/" + id + "/run")).andExpect(status().isAccepted());
        mockMvc.perform(get("/api/cron")).andExpect(jsonPath("$[0].status").value("queued"));
        mockMvc.perform(delete("/api/cron/" + id)).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commands WHERE type='SYNC_CRON'", Integer.class)).isEqualTo(3);
    }

    @Test
    void partialUpdateProtectsBuiltInAndValidationPrecedesWrites() throws Exception {
        jdbcTemplate.update("INSERT INTO cron_jobs(name,schedule,prompt,enabled,built_in) VALUES('builtin','0 9 * * *','original',0,1)");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM cron_jobs WHERE name='builtin'", Long.class);
        mockMvc.perform(patch("/api/cron/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.builtIn").value(true))
                .andExpect(jsonPath("$.promptText").value("original"));
        int commands = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commands", Integer.class);
        mockMvc.perform(put("/api/cron/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"expression\":\"999-999 / - * *\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid cron")));
        mockMvc.perform(delete("/api/cron/" + id)).andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("SELECT schedule FROM cron_jobs WHERE id=?", String.class,id)).isEqualTo("0 9 * * *");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commands", Integer.class)).isEqualTo(commands);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter='|', value={
        "0 10 * * MON|2026-09-09T12:00:00-04:00|2026-09-14T10:00-04:00",
        "*/15 * * * *|2026-09-09T12:01:00-04:00|2026-09-09T12:15-04:00",
        "0 0 9-17 * * MON-FRI|2026-09-11T17:00:00-04:00|2026-09-14T09:00-04:00",
        "0 9 1 * *|2026-09-09T12:00:00-04:00|2026-10-01T09:00-04:00",
        "0 0 9 * JAN,MAR *|2026-09-09T12:00:00-04:00|2027-01-01T09:00-05:00",
        "30 2 * * *|2026-03-07T03:00:00-05:00|2026-03-09T02:30-04:00",
        "30 1 * * *|2026-11-01T01:45:00-04:00|2026-11-01T01:30-05:00",
        "* * * * *|2026-09-09T12:00:01-04:00|2026-09-09T12:01-04:00"
    })
    void nextRunUsesFullExpressionAndTimezone(String expression, String now, String expected) {
        org.assertj.core.api.Assertions.assertThat(CronController.nextRun(expression,
                java.time.ZoneId.of("America/New_York"), java.time.ZonedDateTime.parse(now))).isEqualTo(expected);
    }

    @Test
    void usesPublishedRuntimeTimezoneAndExecutionStatus() throws Exception {
        jdbcTemplate.update("INSERT INTO settings(key,value) VALUES('cron.runtime-timezone','Pacific/Auckland')");
        jdbcTemplate.update("INSERT INTO cron_jobs(name,schedule,prompt) VALUES('zoned','0 9 * * *','hello')");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM cron_jobs WHERE name='zoned'",Long.class);
        jdbcTemplate.update("INSERT INTO cron_execution(job_id,status,message,updated_at) VALUES(?,'failed','Execution failed; check the bot logs for details.','2026-09-09T12:00:00Z')",id);
        mockMvc.perform(get("/api/cron"))
                .andExpect(jsonPath("$[0].timezone").value("Pacific/Auckland"))
                .andExpect(jsonPath("$[0].status").value("failed"))
                .andExpect(jsonPath("$[0].lastRunLog").value("Execution failed; check the bot logs for details."));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"- * * * *", "/ * * * *", "1- * * * *", "60 * * * *", "0 25 * * *", "0 0 * 13 *", "0 0 * * 8", "0 0/0 * * * *"})
    void invalidExpressionsNeverWrite(String expression) throws Exception {
        mockMvc.perform(post("/api/cron").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"invalid\",\"expression\":\"" + expression + "\",\"promptText\":\"hello\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cron_jobs",Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commands",Integer.class)).isZero();
    }
}
