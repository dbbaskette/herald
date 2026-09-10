package com.herald.ui;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SettingsControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RuntimeSettingsClient runtime;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM settings");
        jdbc.update("INSERT INTO settings(key,value) VALUES('agent.persona','Before')");
        when(runtime.snapshot()).thenReturn(Map.of("agent.persona",
                new RuntimeSettingsClient.EffectiveSetting("Runtime", "environment:HERALD_AGENT_PERSONA", "HERALD_AGENT_PERSONA")));
    }
    @AfterEach void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_settings_insert");
        jdbc.update("DELETE FROM settings");
    }
    @Test void successfulAtomicSaveDistinguishesPersistedFromRuntime() throws Exception {
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agent.persona\":\"Saved\",\"cron.timezone\":\"UTC\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.savedSettings['agent.persona']").value("Saved"))
                .andExpect(jsonPath("$.settings['agent.persona'].effective").value("Runtime"))
                .andExpect(jsonPath("$.settings['agent.persona'].source").value("environment:HERALD_AGENT_PERSONA"))
                .andExpect(jsonPath("$.settings['agent.persona'].application").value("configuration-managed"))
                .andExpect(jsonPath("$.settings['agent.persona'].restartRequired").value(true));
        assertThat(jdbc.queryForObject("SELECT value FROM settings WHERE key='cron.timezone'", String.class)).isEqualTo("UTC");
    }
    @Test void invalidTimezoneRejectsWholeUpdateBeforeWrites() throws Exception {
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agent.persona\":\"After\",\"cron.timezone\":\"Not/AZone\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.validationErrors['cron.timezone']").isString());
        assertThat(jdbc.queryForObject("SELECT value FROM settings WHERE key='agent.persona'", String.class)).isEqualTo("Before");
    }
    @Test void rejectsEveryInvalidContextBoundary() throws Exception {
        for (String value : new String[]{"", "0", "-1", "1.5", "no", "2000001", "999999999999", " 10"}) {
            mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"agent.max-context-tokens\":\"" + value + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.validationErrors['agent.max-context-tokens']").isString());
        }
        for (String value : new String[]{"1", "2000000"}) {
            mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"agent.max-context-tokens\":\"" + value + "\"}"))
                    .andExpect(status().isOk());
        }
    }
    @Test void secondWriteFailureRollsBackFirstWrite() throws Exception {
        jdbc.execute("CREATE TRIGGER fail_settings_insert BEFORE INSERT ON settings WHEN NEW.key='weather.location' "
                + "BEGIN SELECT RAISE(ABORT,'fixture write failure'); END");
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agent.persona\":\"After\",\"weather.location\":\"Fixture\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("save-database-failed"));
        assertThat(jdbc.queryForObject("SELECT value FROM settings WHERE key='agent.persona'", String.class)).isEqualTo("Before");
    }
    @Test void unavailableBotIsUnknownAndInternalSettingsAreNotExposed() throws Exception {
        when(runtime.snapshot()).thenReturn(Map.of());
        jdbc.update("INSERT INTO settings(key,value) VALUES('google.client-secret','fixture-secret')");
        mvc.perform(get("/api/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings['agent.persona'].application").value("unknown"))
                .andExpect(jsonPath("$.settings['agent.persona'].source").value("unknown"))
                .andExpect(jsonPath("$.savedSettings['google.client-secret']").doesNotExist());
    }
    @Test void runtimeMatchesAfterConfigurationRestartWithoutPretendingSaveAppliedIt() throws Exception {
        when(runtime.snapshot()).thenReturn(Map.of("agent.persona",
                new RuntimeSettingsClient.EffectiveSetting("Before", "configuration:fixture.yaml", "HERALD_AGENT_PERSONA")));
        mvc.perform(get("/api/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings['agent.persona'].application").value("matches-runtime"))
                .andExpect(jsonPath("$.settings['agent.persona'].restartRequired").value(false));
    }
    @Test void malformedAndUnsupportedRequestsReturnStructuredValidation() throws Exception {
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content("["))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation-failed"));
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content("{\"google.client-secret\":\"secret\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.validationErrors['google.client-secret']").isString());
    }
    @Test void loadAndUnexpectedSaveFailuresHaveDistinctTypedOutcomes() {
        var broken = mock(JdbcTemplate.class);
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var controller = new SettingsController(broken, manager, runtime);
        doThrow(new IllegalStateException("fixture load failure")).when(broken)
                .query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class));
        assertThat(controller.getAll().getBody().error()).isEqualTo("load-failed");
        doThrow(new IllegalStateException("fixture unexpected write failure")).when(broken)
                .update(anyString(), any(Object[].class));
        assertThat(controller.update(Map.of("agent.persona", "Draft")).getBody().error()).isEqualTo("save-failed");
        verify(manager).rollback(any());
    }
    @Test void runtimeSnapshotFailureCannotTurnACommittedSaveIntoFailure() throws Exception {
        when(runtime.snapshot()).thenThrow(new IllegalStateException("fixture unavailable"));
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content("{\"agent.persona\":\"Saved\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.settings['agent.persona'].application").value("unknown"));
    }
}
