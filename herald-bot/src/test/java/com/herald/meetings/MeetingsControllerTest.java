package com.herald.meetings;
import com.herald.api.MeetingsController;
import com.herald.config.HeraldConfig;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class MeetingsControllerTest {
    @Test void rejectsIncompleteWebhookBeforeItCanPoisonDurableQueue() {
        var service=mock(MeetingIngestService.class); var config=mock(HeraldConfig.class);
        when(config.cronTimezone()).thenReturn("UTC");
        var controller=new MeetingsController(service,mock(MeetingNotesCatalog.class),config);
        var metadata=new MeetingsController.Meeting("fixture","fixture","Fixture","2026-09-01",1L,List.of());
        for (String summary:new String[] {null,""," \n "}) {
            var ack=controller.ingest(new MeetingsController.IngestPayload("meeting.completed",metadata,summary,List.of()));
            assertThat(ack.getStatusCode().value()).isEqualTo(400);
        }
        verifyNoInteractions(service);
        when(service.claimAndIngest(any(),eq("webhook"))).thenReturn(true);
        assertThat(controller.ingest(new MeetingsController.IngestPayload("meeting.completed",metadata,"Full summary",List.of()))
                .getStatusCode().value()).isEqualTo(202);
    }

    @Test void backfillAcknowledgesQueueAndReturnsExactIdsForProgress() {
        var catalog=mock(MeetingNotesCatalog.class); var service=mock(MeetingIngestService.class);
        var config=mock(HeraldConfig.class); when(config.cronTimezone()).thenReturn("UTC");
        var meeting=new MeetingDigest("fixture","fixture","Fixture","2026-09-01",1,"done",List.of(),"Summary",List.of());
        when(catalog.findByDateRange(LocalDate.of(2026,9,1),LocalDate.of(2026,9,2))).thenReturn(List.of(meeting));
        when(service.backfillAsync(List.of(meeting),"backfill")).thenReturn(1);
        var controller=new MeetingsController(service,catalog,config);
        var ack=controller.backfill("2026-09-01","2026-09-02",null);
        assertThat(ack.getStatusCode().value()).isEqualTo(202);
        assertThat(ack.getBody().queued()).isEqualTo(1);
        assertThat(ack.getBody().meetingIds()).containsExactly("fixture");
        assertThat(controller.retry("missing").getStatusCode().value()).isEqualTo(409);
        when(service.retry("fixture")).thenReturn(true);
        assertThat(controller.retry("fixture").getStatusCode().value()).isEqualTo(202);
    }
}
