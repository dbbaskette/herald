package com.herald.ui;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.herald.ui.repository.MessageRepository;

@RestController
@RequestMapping("/api/messages")
class MessagesController {

    private final MessageRepository repository;

    MessagesController(MessageRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestParam(defaultValue = "50") int limit) {
        return repository.listRecent(limit);
    }

    @DeleteMapping
    ResponseEntity<Void> deleteAll() {
        repository.deleteAll();
        return ResponseEntity.noContent().build();
    }
}
