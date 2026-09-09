package com.herald.meetings;

import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.sqlite.SQLiteDataSource;
import static org.assertj.core.api.Assertions.*;

class MeetingIngestLedgerTest {
    @TempDir Path dir;
    JdbcTemplate jdbc;
    MeetingIngestLedger ledger;
    final MeetingDigest meeting=new MeetingDigest("fixture-id","fixture","Fixture meeting","2026-09-01",10,"done",List.of(),"Summary",List.of());
    @BeforeEach void setup() {
        SQLiteDataSource ds=new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:"+dir.resolve("jobs.sqlite"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds); ledger=at(1000);
    }
    MeetingIngestLedger at(long millis) { return new MeetingIngestLedger(jdbc,Clock.fixed(Instant.ofEpochMilli(millis),ZoneOffset.UTC)); }
    @Test void restartRecoversPersistedPayloadAndExpiredLeaseWithFencing() {
        assertThat(ledger.enqueue(meeting,"webhook")).isTrue();
        var first=ledger.acquire(meeting.id(),100);
        assertThat(at(1050).acquire(meeting.id(),100)).isNull();
        var restarted=at(1101);
        assertThat(restarted.recoverable()).containsExactly(meeting.id());
        var second=restarted.acquire(meeting.id(),100);
        assertThat(second.meeting()).isEqualTo(meeting);
        assertThatThrownBy(() -> restarted.finish(first,null)).isInstanceOf(IllegalStateException.class);
        restarted.finish(second,null);
        assertThat(restarted.isIngested(meeting.id())).isTrue();
        assertThat(restarted.recoverable()).isEmpty();
    }
    @Test void concurrentSourcesAcquireOnlyOneLease() throws Exception {
        ledger.enqueue(meeting,"webhook");
        try (var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Boolean> attempt=() -> { start.await(); return ledger.acquire(meeting.id(),100)!=null; };
            var a=executor.submit(attempt); var b=executor.submit(attempt); start.countDown();
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void retryRetainsEnrichmentAndSuccessfulEffectsButDoesNotMarkFailureDone() {
        ledger.enqueue(meeting,"backfill"); var claim=ledger.acquire(meeting.id(),100);
        ledger.checkpoint(claim,"Saved"); ledger.recordEffect(claim,"reminder-0");
        ledger.finish(claim,"recap unavailable");
        assertThat(ledger.isIngested(meeting.id())).isFalse();
        assertThat(ledger.progress().getFirst().state()).isEqualTo("failed");
        assertThat(ledger.retry(meeting.id())).isTrue();
        var retry=ledger.acquire(meeting.id(),100);
        assertThat(retry.reply()).isEqualTo("Saved");
        assertThat(ledger.effectDone(retry,"reminder-0")).isTrue();
        ledger.finish(retry,null);
        assertThat(ledger.retry(meeting.id())).isFalse();
    }
    @Test void incompleteCatalogPayloadCannotReserveIdBeforeCompletePayloadArrives() {
        for (String summary:new String[] {null,""," \n "}) {
            var incomplete=new MeetingDigest(meeting.id(),meeting.slug(),meeting.title(),meeting.startedAt(),
                    10,"done",List.of(),summary,List.of());
            assertThat(ledger.enqueue(incomplete,"catchup")).isFalse();
        }
        assertThat(ledger.progress()).isEmpty();
        assertThat(ledger.enqueue(meeting,"webhook")).isTrue();
        assertThat(ledger.acquire(meeting.id(),100).meeting()).isEqualTo(meeting);
    }

    @Test void legacyClaimNeedsExplicitReviewRatherThanClaimingSuccess() {
        jdbc.update("INSERT INTO meetings_ingested(meeting_id) VALUES (?)",meeting.id());
        assertThat(ledger.enqueue(meeting,"catchup")).isFalse();
        assertThat(ledger.isIngested(meeting.id())).isFalse();
        assertThat(ledger.progress().getFirst().error()).contains("Legacy claim");
        assertThat(ledger.recoverable()).isEmpty();
        assertThat(ledger.retry(meeting.id())).isTrue();
    }
}
