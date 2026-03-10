package com.herald.ui;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.herald.ui.repository.CommandRepository;
import com.herald.ui.repository.CronJobRepository;

@RestController
@RequestMapping("/api/cron")
class CronController {

    private final CronJobRepository cronJobRepository;
    private final CommandRepository commandRepository;

    CronController(CronJobRepository cronJobRepository, CommandRepository commandRepository) {
        this.cronJobRepository = cronJobRepository;
        this.commandRepository = commandRepository;
    }

    @GetMapping
    List<Map<String, Object>> list() {
        return cronJobRepository.listAll();
    }

    @PutMapping("/{id}")
    ResponseEntity<Map<String, Object>> update(@PathVariable long id,
            @RequestBody CronJobUpdateRequest request) {
        Map<String, Object> existing = cronJobRepository.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        cronJobRepository.update(id, request.schedule(), request.prompt(), request.enabled());
        Map<String, Object> updated = cronJobRepository.getById(id);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/run")
    ResponseEntity<Map<String, Object>> run(@PathVariable long id) {
        Map<String, Object> existing = cronJobRepository.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> command = commandRepository.insert("RUN_CRON", String.valueOf(id));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(command);
    }

    record CronJobUpdateRequest(String schedule, String prompt, Boolean enabled) {
    }
}
