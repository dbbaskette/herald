package com.herald.ui;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/memory")
class MemoryController {

    private final MemoryRepository repository;

    MemoryController(MemoryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    List<MemoryEntry> list() {
        return repository.listAll().stream()
                .map(MemoryController::toEntry)
                .toList();
    }

    @PutMapping("/{key}")
    ResponseEntity<MemoryEntry> upsert(@PathVariable String key, @RequestBody ValuePayload payload) {
        if (payload.value() == null || payload.value().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Map<String, Object> row = repository.upsert(key, payload.value());
        return ResponseEntity.ok(toEntry(row));
    }

    @DeleteMapping("/{key}")
    ResponseEntity<Void> delete(@PathVariable String key) {
        boolean deleted = repository.delete(key);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static MemoryEntry toEntry(Map<String, Object> row) {
        return new MemoryEntry(
                (String) row.get("key"),
                (String) row.get("value"),
                row.get("updated_at") != null ? row.get("updated_at").toString() : null);
    }

    record MemoryEntry(String key, String value, String lastUpdated) {
    }

    record ValuePayload(String value) {
    }
}
