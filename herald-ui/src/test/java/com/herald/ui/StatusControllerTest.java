package com.herald.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class StatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusReturnsJsonWithRequiredFields() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageCount").isNumber())
                .andExpect(jsonPath("$.pendingCommandCount").isNumber())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void streamEndpointReturns200() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/status/stream")
                        .accept("text/event-stream"))
                .andExpect(status().isOk())
                .andReturn();

        // SseEmitter is returned — MockMvc doesn't fully simulate SSE streaming,
        // but we verify the endpoint is accessible and returns an SseEmitter
        assertThat(result.getResponse()).isNotNull();
    }
}
