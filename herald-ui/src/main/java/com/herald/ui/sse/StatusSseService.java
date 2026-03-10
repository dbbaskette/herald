package com.herald.ui.sse;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class StatusSseService {

    private static final Logger log = LoggerFactory.getLogger(StatusSseService.class);

    private final JdbcTemplate jdbcTemplate;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public StatusSseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    @Scheduled(fixedDelay = 5000)
    void pushStatus() {
        if (emitters.isEmpty()) {
            return;
        }

        Map<String, Object> status = buildStatus();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("status").data(status));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }
    }

    public Map<String, Object> buildStatus() {
        Integer messageCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Integer.class);
        Integer pendingCommandCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM commands WHERE status = 'pending'", Integer.class);

        return Map.of(
                "messageCount", messageCount != null ? messageCount : 0,
                "pendingCommandCount", pendingCommandCount != null ? pendingCommandCount : 0,
                "timestamp", Instant.now().toString());
    }
}
