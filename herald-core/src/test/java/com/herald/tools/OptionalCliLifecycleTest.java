package com.herald.tools;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class OptionalCliLifecycleTest {
    @Test void disabledAndTaskModesNeverExecuteCliProbes() {
        for (MockEnvironment env : new MockEnvironment[]{
                new MockEnvironment().withProperty("herald.google.enabled","false").withProperty("herald.reminders.enabled","false"),
                new MockEnvironment().withProperty("agents","fixture.md")}) {
            AtomicInteger probes = new AtomicInteger();
            GwsAvailabilityChecker google = new GwsAvailabilityChecker(command -> {
                probes.incrementAndGet(); return new GwsAvailabilityChecker.CommandResult(0,"fixture");
            });
            RemindersAvailabilityChecker reminders = new RemindersAvailabilityChecker(command -> {
                probes.incrementAndGet(); return new RemindersAvailabilityChecker.CommandResult(0,"fixture");
            }, "macOS");
            google.configureLifecycle(env); reminders.configureLifecycle(env);
            google.checkGwsAvailability(); reminders.checkAvailability();
            assertThat(probes.get()).isZero();
            assertThat(google.isAvailable()).isFalse(); assertThat(reminders.isAvailable()).isFalse();
        }
    }
}
