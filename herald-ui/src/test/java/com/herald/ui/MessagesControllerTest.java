package com.herald.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MessagesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void listReturnsPagedResponseInitially() throws Exception {
        mockMvc.perform(get("/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.totalPages", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.number", is(0)));
    }

    @Test
    void listRespectsSizeParam() throws Exception {
        // Insert test data
        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update("INSERT INTO messages (role, content) VALUES (?, ?)", "user", "msg" + i);
        }

        mockMvc.perform(get("/api/messages").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements", is(5)))
                .andExpect(jsonPath("$.totalPages", is(2)));

        // Clean up
        jdbcTemplate.update("DELETE FROM messages");
    }

    @Test
    void deleteAllReturns204() throws Exception {
        jdbcTemplate.update("INSERT INTO messages (role, content) VALUES (?, ?)", "user", "to-delete");

        mockMvc.perform(delete("/api/messages"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }
}
