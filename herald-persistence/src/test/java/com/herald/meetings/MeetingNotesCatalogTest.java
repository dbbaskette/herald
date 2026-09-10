package com.herald.meetings;
import com.herald.config.HeraldConfig;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class MeetingNotesCatalogTest {
    @TempDir Path dir;
    @Test void findsCompletedMeetingsAcrossDowntimeAndLateCompletions() throws Exception {
        Path db=dir.resolve("catalog.sqlite");
        SQLiteDataSource ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+db);
        JdbcTemplate jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE meetings(id TEXT,slug TEXT,title TEXT,started_at TEXT,duration_s REAL,status TEXT,deleted_at TEXT)");
        jdbc.update("INSERT INTO meetings VALUES ('old','old','Fixture old','2026-08-01',1.4,'done',NULL)");
        jdbc.update("INSERT INTO meetings VALUES ('new','new','Fixture new','2026-09-09',2.6,'processing',NULL)");
        Files.createDirectories(dir.resolve("meetings/old")); Files.writeString(dir.resolve("meetings/old/summary.md"),"Fixture summary");
        HeraldConfig config=mock(HeraldConfig.class);
        when(config.meetingNotesDbPath()).thenReturn(db.toString()); when(config.meetingNotesDir()).thenReturn(dir.toString());
        var catalog=new MeetingNotesCatalog(config);
        Files.writeString(dir.resolve("meetings/old/summary.md"),"  ");
        assertThat(catalog.findCompleted()).isEmpty();
        Files.writeString(dir.resolve("meetings/old/summary.md"),"Fixture summary");
        assertThat(catalog.findCompleted()).extracting(MeetingDigest::id).containsExactly("old");
        assertThat(catalog.findCompleted().getFirst().durationS()).isEqualTo(1);
    }
}
