package com.herald.api;

import com.herald.agent.ModelSwitcher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/model")
class ModelController {

    private final ModelSwitcher modelSwitcher;

    ModelController(ModelSwitcher modelSwitcher) {
        this.modelSwitcher = modelSwitcher;
    }

    @GetMapping
    ModelStatus status() {
        return new ModelStatus(
                modelSwitcher.getActiveProvider(),
                modelSwitcher.getActiveModel(),
                modelSwitcher.getAvailableProviderDefaults());
    }

    @PostMapping
    ResponseEntity<ModelStatus> switchModel(@RequestBody SwitchRequest req) {
        try {
            modelSwitcher.switchModel(req.provider(), req.model());
            return ResponseEntity.ok(status());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    new ModelStatus(req.provider(), req.model(), Map.of("error", e.getMessage())));
        }
    }

    record ModelStatus(String provider, String model, Map<String, String> available) {}
    record SwitchRequest(String provider, String model) {}
}
