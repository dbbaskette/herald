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
    Map<String, Object> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        List<Map<String, Object>> all = repository.listFiltered(search, startDate, endDate);
        int totalElements = all.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalElements / size));
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        List<Map<String, Object>> content = all.subList(fromIndex, toIndex);
        return Map.of(
                "content", content,
                "number", page,
                "totalPages", totalPages,
                "totalElements", totalElements
        );
    }

    @DeleteMapping
    ResponseEntity<Void> deleteAll() {
        repository.deleteAll();
        return ResponseEntity.noContent().build();
    }
}
