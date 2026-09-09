package com.herald.agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Properties;
import static org.assertj.core.api.Assertions.*;
class MemoryContextEvidenceTest {
    @TempDir Path root;
    @Test void recordsOnlyActualExistingPathsAndCompletesAsInactive() throws Exception {
        Files.writeString(root.resolve("a.md"),"A");
        var evidence=new MemoryContextEvidence(root,"web-test");evidence.record("a.md");evidence.record("missing.md");evidence.record("../escape.md");
        Path snapshot;try(var list=Files.list(root.resolve(".memory-context"))){snapshot=list.findFirst().orElseThrow();}
        Properties props=read(snapshot);assertThat(props.getProperty("active")).isEqualTo("true");assertThat(props.getProperty("path.0")).isEqualTo("a.md");assertThat(props.getProperty("path.1")).isNull();
        evidence.publish(false);assertThat(read(snapshot).getProperty("active")).isEqualTo("false");
        evidence.attribution("a.md");try(var list=Files.list(root.resolve(".memory-attribution"))){assertThat(read(list.findFirst().orElseThrow()).getProperty("conversationId")).isEqualTo("web-test");}
    }
    private Properties read(Path path)throws Exception {Properties props=new Properties();try(var in=Files.newInputStream(path)){props.load(in);}return props;}
}
