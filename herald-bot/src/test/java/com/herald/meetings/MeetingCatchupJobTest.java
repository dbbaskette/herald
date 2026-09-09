package com.herald.meetings;
import com.herald.config.HeraldConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class MeetingCatchupJobTest {
    @Test void catchupUsesHistoryRatherThanTodayOnly() {
        var catalog=mock(MeetingNotesCatalog.class); var service=mock(MeetingIngestService.class);
        var config=mock(HeraldConfig.class); when(config.cronTimezone()).thenReturn("UTC");
        var old=new MeetingDigest("fixture","fixture","Fixture old","2020-01-01",1,"done",List.of(),"Summary",List.of());
        when(catalog.findCompleted()).thenReturn(List.of(old));
        new MeetingCatchupJob(catalog,service,config).run();
        verify(service).claimAndIngest(old,"catchup"); verify(catalog).findCompleted(); verifyNoMoreInteractions(catalog);
    }
}
