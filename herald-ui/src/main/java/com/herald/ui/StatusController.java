package com.herald.ui;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.herald.ui.sse.StatusSseService;

@RestController
@RequestMapping("/api/status")
class StatusController {

    private final StatusSseService statusSseService;

    StatusController(StatusSseService statusSseService) {
        this.statusSseService = statusSseService;
    }

    @GetMapping
    Map<String, Object> status() {
        return statusSseService.buildStatus();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        return statusSseService.register();
    }
}
