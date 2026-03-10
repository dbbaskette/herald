package com.herald.ui;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.herald.ui.repository.CommandRepository;

@RestController
@RequestMapping("/api/commands")
class CommandsController {

    private final CommandRepository repository;

    CommandsController(CommandRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody CommandPayload payload) {
        Map<String, Object> command = repository.insert(payload.type(), payload.payload());
        return ResponseEntity.status(HttpStatus.CREATED).body(command);
    }

    record CommandPayload(String type, String payload) {
    }
}
