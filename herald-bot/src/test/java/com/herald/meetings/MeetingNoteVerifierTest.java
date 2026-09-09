package com.herald.meetings;
import com.herald.config.HeraldConfig;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class MeetingNoteVerifierTest {
    @TempDir Path dir;
    @Test void requiresExactSourceAndFullDurableSummary() throws Exception {
        HeraldConfig config=mock(HeraldConfig.class);
        when(config.memoriesDir()).thenReturn(dir.toString()); when(config.obsidianVaultPath()).thenReturn("");
        var verifier=new MeetingNoteVerifier(config);
        var meeting=new MeetingDigest("fixture-id", "slug","Fixture","2026-09-01",1,"done",List.of(),"Complete summary\nSecond line",List.of());
        Path note=dir.resolve("meeting.md");
        Files.writeString(note,"Source: fixture-id-other\nComplete summary\nSecond line");
        assertThat(verifier.isSaved(meeting)).isFalse();
        Files.writeString(note,"Source: fixture-id\nComplete summary");
        assertThat(verifier.isSaved(meeting)).isFalse();
        Files.writeString(note,"Source: fixture-id\nComplete summary\nSecond line");
        assertThat(verifier.isSaved(meeting)).isTrue();
    }
}
