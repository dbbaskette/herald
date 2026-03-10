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
class CommandsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM commands");
    }

    @Test
    void createCommandReturns201WithPendingStatus() throws Exception {
        mockMvc.perform(post("/api/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"SEND_MESSAGE\", \"payload\": \"hello\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SEND_MESSAGE"))
                .andExpect(jsonPath("$.payload").value("hello"))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.id").isNumber());
    }
}
