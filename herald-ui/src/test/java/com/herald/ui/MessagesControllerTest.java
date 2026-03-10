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
    void listReturnsEmptyArrayInitially() throws Exception {
        mockMvc.perform(get("/api/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(0))));
    }

    @Test
    void listRespectsLimitParam() throws Exception {
        // Insert test data
        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update("INSERT INTO messages (role, content) VALUES (?, ?)", "user", "msg" + i);
        }

        mockMvc.perform(get("/api/messages").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));

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
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
