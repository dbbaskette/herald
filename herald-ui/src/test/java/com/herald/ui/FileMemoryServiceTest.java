package com.herald.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Properties;
import static org.assertj.core.api.Assertions.*;

class FileMemoryServiceTest {
    @TempDir Path root;
    @Test void versionedAtomicEditingRejectsConflictAndOversizedText() throws Exception {
        Files.writeString(root.resolve("a.md"),"original"); var service=new FileMemoryService(root.toString());
        var old=service.read("a.md");var saved=service.save("a.md","edited",old.version());
        assertThat(saved.version()).isNotEqualTo(old.version());assertThat(Files.readString(root.resolve("a.md"))).isEqualTo("edited");
        assertThatThrownBy(()->service.save("a.md","lost update",old.version())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThatThrownBy(()->service.save("a.md","x".repeat(FileMemoryService.MAX_BYTES+1),saved.version())).hasMessageContaining("413");
        assertThat(Files.readString(root.resolve("a.md"))).isEqualTo("edited");
    }
    @Test void trashRestoresWithoutOverwritingAndCannotBeBrowsed() throws Exception {
        Files.createDirectories(root.resolve("notes")); Files.writeString(root.resolve("notes/a.md"),"original");var service=new FileMemoryService(root.toString());
        var deleted=service.trash("notes/a.md",service.read("notes/a.md").version());
        assertThat(Files.readString(root.resolve(deleted.trashPath()))).isEqualTo("original"); assertThat(service.pages()).isEmpty();
        Files.writeString(root.resolve("notes/a.md"),"replacement");
        assertThatThrownBy(()->service.restore(deleted.token())).hasMessageContaining("409");
        assertThat(Files.readString(root.resolve("notes/a.md"))).isEqualTo("replacement");
        Files.delete(root.resolve("notes/a.md"));assertThat(service.restore(deleted.token()).content()).isEqualTo("original");
    }
    @Test void rejectsTraversalAbsoluteSymlinksAndTamperedRecoveryManifest() throws Exception {
        var service=new FileMemoryService(root.toString());Files.writeString(root.resolve("a.md"),"original");
        for(String path:new String[]{"../a.md","notes/../a.md",root.resolve("a.md").toString(),".memory-trash/x.md","notes\\a.md"}) assertThatThrownBy(()->service.read(path)).isInstanceOf(ResponseStatusException.class);
        Path outside=Files.createTempDirectory(root.getParent(),"memory-outside-");
        try {
            Files.writeString(outside.resolve("secret.md"),"secret");Files.createSymbolicLink(root.resolve("link"),outside);
            assertThatThrownBy(()->service.read("link/secret.md")).hasMessageContaining("Symbolic");
            assertThat(service.pages()).doesNotContain(root.resolve("link/secret.md"));
            var deleted=service.trash("a.md",service.read("a.md").version());
            Files.writeString(root.resolve(".memory-trash/"+deleted.token()+"/original-path"),"../escape.md");
            assertThatThrownBy(()->service.restore(deleted.token())).hasMessageContaining("Traversal");
        } finally {Files.deleteIfExists(outside.resolve("secret.md"));Files.deleteIfExists(outside);}
    }
    @Test void oversizedFilesAreListedButNeverLoadedPartially() throws Exception {
        Files.writeString(root.resolve("large.md"),"x".repeat(FileMemoryService.MAX_BYTES+1));
        var controller=new FileMemoryController(root.toString());
        assertThat(controller.list("large","").get("unknown").getFirst().oversized()).isTrue();
        assertThat(controller.content("large.md").getStatusCode().value()).isEqualTo(413);
    }
    @Test void searchesFullTextTagsAndReportsOptionalAttribution() throws Exception {
        Files.writeString(root.resolve("MEMORY.md"),"# Index");
        Files.writeString(root.resolve("a.md"),"---\ntype: concept\ntags: [java, spring]\nconversation_id: web-test\ndate: 2026-09-09\n---\nDeep searchable text #backend");
        var controller=new FileMemoryController(root.toString());
        var page=controller.list("searchable","backend").get("concept").getFirst();
        assertThat(page.tags()).contains("java","spring","backend");assertThat(page.conversationId()).isEqualTo("web-test");
        assertThat(controller.list("","missing").values()).allMatch(java.util.List::isEmpty);
        assertThat(controller.listGroupedByType().get("index")).hasSize(1);
    }
    @Test void contextRequiresFreshActiveEvidenceAndExistingSafePaths() throws Exception {
        Files.writeString(root.resolve("a.md"),"a"); var controller=new FileMemoryController(root.toString());
        Properties props=new Properties();props.setProperty("active","true");props.setProperty("path.0","a.md");props.setProperty("path.1","../escape.md");props.setProperty("observedAt",Instant.now().toString());writeEvidence(props);
        assertThat(controller.context("web-test")).containsEntry("active",true).containsEntry("paths",java.util.List.of("a.md"));
        props.setProperty("active","false");writeEvidence(props);assertThat(controller.context("web-test")).containsEntry("active",false).containsEntry("paths",java.util.List.of());
        props.setProperty("active","true");props.setProperty("observedAt",Instant.now().minusSeconds(61).toString());writeEvidence(props);assertThat(controller.context("web-test")).containsEntry("active",false);
        assertThat(controller.context("unknown")).containsEntry("active",false);
    }
    private void writeEvidence(Properties props) throws Exception {
        Files.createDirectories(root.resolve(".memory-context"));String hash=FileMemoryService.version("web-test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try(var out=Files.newOutputStream(root.resolve(".memory-context/"+hash+".properties"))){props.store(out,"");}
    }
}
