package com.herald.api;

import com.herald.agent.ModelSwitcher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/model")
class ModelController {

    private final ModelSwitcher modelSwitcher;
    private final LmStudioModelDiscovery lmStudioDiscovery;

    ModelController(ModelSwitcher modelSwitcher, LmStudioModelDiscovery lmStudioDiscovery) {
        this.modelSwitcher = modelSwitcher;
        this.lmStudioDiscovery = lmStudioDiscovery;
    }

    @GetMapping
    ModelStatus status() {
        return new ModelStatus(
                modelSwitcher.getActiveProvider(),
                modelSwitcher.getActiveModel(),
                modelSwitcher.getAvailableProviderDefaults(),
                modelSwitcher.getProviderModelCatalog());
    }

    @PostMapping
    ResponseEntity<ModelStatus> switchModel(@RequestBody SwitchRequest req) {
        try {
            modelSwitcher.switchModel(req.provider(), req.model());
            return ResponseEntity.ok(status());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    new ModelStatus(req.provider(), req.model(),
                            Map.of("error", e.getMessage()), Map.of()));
        }
    }

    /**
     * Re-query LM Studio for its loaded models and refresh the catalog, so a model
     * swapped in LM Studio shows up in the picker without restarting the bot.
     * Returns the updated status (catalog now reflects what LM Studio reports).
     */
    @PostMapping("/rescan")
    ModelStatus rescan() {
        lmStudioDiscovery.rescan();
        return status();
    }

    record ModelStatus(String provider, String model,
                       Map<String, String> available,
                       Map<String, List<String>> catalog) {}
    record SwitchRequest(String provider, String model) {}
}
