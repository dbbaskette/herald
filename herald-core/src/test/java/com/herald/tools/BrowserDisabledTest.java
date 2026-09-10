package com.herald.tools;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class BrowserDisabledTest {
    @Test void disabledCapabilityHasNoToolsAdvisorsOrRuntimeResources() {
        new ApplicationContextRunner().withUserConfiguration(BrowserTools.class,BrowserApproval.class,BrowserScreenshotAdvisor.class)
                .run(context -> assertThat(context).doesNotHaveBean(BrowserTools.class).doesNotHaveBean(BrowserScreenshotAdvisor.class).doesNotHaveBean(BrowserApproval.class));
    }
}
