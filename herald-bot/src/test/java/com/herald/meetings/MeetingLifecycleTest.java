package com.herald.meetings;

import com.herald.api.MeetingsController;
import com.herald.config.HeraldConfig;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MeetingLifecycleTest {
    @Configuration(proxyBeanMethods=false) @EnableScheduling static class Scheduling {}
    @Test void disabledOrTaskModeCreatesNoMeetingBeansAndRegistersNoRecoveryOrCatchup() {
        for (String setting : Set.of("herald.meetingnotes.enabled=false", "agents=fixture.md", "herald.persistence.enabled=false")) {
            HeraldConfig config=mock(HeraldConfig.class);
            new ApplicationContextRunner().withBean(HeraldConfig.class, () -> config)
                    .withUserConfiguration(Scheduling.class,MeetingNotesCatalog.class,MeetingIngestLedger.class,
                            MeetingIngestService.class,MeetingCatchupJob.class,MeetingNoteVerifier.class,MeetingsController.class)
                    .withPropertyValues("herald.memory.db-path=/unused-fixture-no-probe.sqlite", setting)
                    .run(c -> {
                        assertThat(c).hasNotFailed().doesNotHaveBean(MeetingNotesCatalog.class)
                                .doesNotHaveBean(MeetingIngestLedger.class).doesNotHaveBean(MeetingIngestService.class)
                                .doesNotHaveBean(MeetingCatchupJob.class).doesNotHaveBean(MeetingNoteVerifier.class)
                                .doesNotHaveBean(MeetingsController.class);
                        assertThat(c.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
                        verifyNoInteractions(config);
                    });
        }
    }
}
