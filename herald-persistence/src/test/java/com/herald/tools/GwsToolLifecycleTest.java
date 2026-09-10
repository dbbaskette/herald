package com.herald.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GwsToolLifecycleTest {
    @Test void unavailableCliCreatesNoGoogleToolsAndNeedsNoJdbc() {
        GwsAvailabilityChecker checker=mock(GwsAvailabilityChecker.class);
        new ApplicationContextRunner().withBean(GwsAvailabilityChecker.class, () -> checker)
                .withUserConfiguration(GwsToolConfiguration.class).run(c -> {
                    assertThat(c).hasNotFailed();
                    // Spring retains the factory method definition, but exposes no usable object.
                    assertThat(c.getBeanProvider(GwsTools.class).stream()).isEmpty();
                    assertThat(c.getBeansOfType(GwsTools.class)).isEmpty();
                });
    }
    @Test void availableCliRegistersToolsWithoutJdbc() {
        GwsAvailabilityChecker checker=mock(GwsAvailabilityChecker.class);
        when(checker.isAvailable()).thenReturn(true);
        new ApplicationContextRunner().withBean(GwsAvailabilityChecker.class, () -> checker)
                .withUserConfiguration(GwsToolConfiguration.class).run(c ->
                        assertThat(c).hasNotFailed().hasSingleBean(GwsTools.class));
    }
}
