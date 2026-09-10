package com.herald.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class PromptsControllerTest {
    @TempDir Path temp;
    @Test void requiresVersionAndPreservesExternalEdits() throws Exception {
        Path context = temp.resolve("CONTEXT.md");
        Files.writeString(context, "original");
        var controller = new PromptsController(context.toString());
        var first = controller.read("CONTEXT.md");
        String version = first.getHeaders().getETag();
        assertThat(controller.save("CONTEXT.md", new PromptsController.PromptUpdate("draft"), null)
                .getStatusCode().value()).isEqualTo(428);
        Files.writeString(context, "external");
        var conflict = controller.save("CONTEXT.md", new PromptsController.PromptUpdate("draft"), version);
        assertThat(conflict.getStatusCode().value()).isEqualTo(412);
        assertThat(conflict.getBody()).containsEntry("content", "external");
        assertThat(Files.readString(context)).isEqualTo("external");
        var saved = controller.save("CONTEXT.md", new PromptsController.PromptUpdate("merged"), conflict.getHeaders().getETag());
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(saved.getHeaders().getETag()).isEqualTo(DocumentVersions.etag("merged"));
        assertThat(Files.readString(context)).isEqualTo("merged");
    }
    @Test void absentContextCanBeCreatedWithReadVersion() throws Exception {
        Path context = temp.resolve("new/CONTEXT.md");
        var controller = new PromptsController(context.toString());
        var version = controller.read("CONTEXT.md").getHeaders().getETag();
        assertThat(controller.save("CONTEXT.md", new PromptsController.PromptUpdate("new"), version)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(Files.readString(context)).isEqualTo("new");
    }
}
