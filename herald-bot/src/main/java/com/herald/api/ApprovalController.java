package com.herald.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.herald.agent.MemoryApprovalGate;
import com.herald.agent.PendingApproval;
import com.herald.agent.PendingApprovalRegistry;

/**
 * REST API for the Console memory-approval inbox. Lists pending edits and
 * lets the user approve or decline without switching to Telegram.
 */
@RestController
@RequestMapping("/api/approvals")
class ApprovalController {

    private final PendingApprovalRegistry registry;
    private final MemoryApprovalGate memoryApprovalGate;
    private final ObjectMapper objectMapper;

    ApprovalController(PendingApprovalRegistry registry,
                       MemoryApprovalGate memoryApprovalGate,
                       ObjectMapper objectMapper) {
        this.registry = registry;
        this.memoryApprovalGate = memoryApprovalGate;
        this.objectMapper = objectMapper;
    }

    @GetMapping(produces = "application/json")
    List<PendingApproval> list(
            @RequestParam(name = "conversationId", required = false) String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return registry.listForConversation(conversationId);
        }
        return registry.listAll();
    }

    @PostMapping(value = "/{id}/resolve", consumes = "application/json", produces = "application/json")
    ResponseEntity<String> resolve(@PathVariable String id, @RequestBody String body) {
        boolean approved;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> req = objectMapper.readValue(body, Map.class);
            approved = Boolean.TRUE.equals(req.get("approved"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"Invalid request body — expected {\\\"approved\\\": true|false}\"}");
        }

        boolean ok = memoryApprovalGate.resolve(id, approved);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("{\"error\":\"Unknown or already-resolved approval id\"}");
        }
        return ResponseEntity.ok("{\"ok\":true,\"approved\":" + approved + "}");
    }
}
