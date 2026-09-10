package com.herald.api;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class SkillCapabilitiesControllerTest {
    @Test void exposesOnlyConfiguredToolNames() {
        var controller = new SkillCapabilitiesController(List.of("shell", "skills"));
        assertThat(controller.capabilities()).containsEntry("status", "available")
                .containsEntry("tools", List.of("shell", "skills")).hasSize(2);
    }
}
