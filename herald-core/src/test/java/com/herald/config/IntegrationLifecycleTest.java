package com.herald.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class IntegrationLifecycleTest {
    @Test void assistantDefaultsKeepPersistenceAndCronWhileOptionalIntegrationsStayOff() {
        var env=new MockEnvironment().withProperty("herald.memory.db-path","fixture.sqlite");
        assertThat(IntegrationLifecycle.persistenceEnabled(env)).isTrue();
        assertThat(IntegrationLifecycle.cronEnabled(env)).isTrue();
        assertThat(IntegrationLifecycle.meetingNotesEnabled(env)).isFalse();
        assertThat(IntegrationLifecycle.googleEnabled(env)).isFalse();
        assertThat(IntegrationLifecycle.remindersEnabled(env)).isFalse();
        env.withProperty("herald.meetingnotes.enabled","true");
        assertThat(IntegrationLifecycle.meetingNotesEnabled(env)).isTrue();
        env.withProperty("herald.meetingnotes.enabled","");
        assertThat(IntegrationLifecycle.meetingNotesEnabled(env)).isFalse();
        assertThat(IntegrationLifecycle.cronEnabled(env)).isTrue();
        env.withProperty("herald.persistence.enabled","false");
        assertThat(IntegrationLifecycle.persistenceEnabled(env)).isFalse();
        assertThat(IntegrationLifecycle.cronEnabled(env)).isFalse();
    }
    @Test void binderRetainsPathsAndBindsExplicitMeetingAndCronFlagsWithCompatibilityConstructors() {
        var config=new Binder(new MapConfigurationPropertySource(Map.of(
                "herald.meetingnotes.enabled","false","herald.meetingnotes.db-path","fixture.sqlite",
                "herald.meetingnotes.dir","fixture","herald.cron.enabled","false","herald.cron.timezone","UTC")))
                .bind("herald",HeraldConfig.class).get();
        assertThat(config.meetingnotes().enabled()).isFalse();
        assertThat(config.meetingnotes().dbPath()).isEqualTo("fixture.sqlite");
        assertThat(config.cron().enabled()).isFalse();
        assertThat(config.cronTimezone()).isEqualTo("UTC");
        assertThat(new HeraldConfig.MeetingNotes("db","dir").enabled()).isNull();
        assertThat(new HeraldConfig.Cron("UTC").enabled()).isNull();
    }
}
