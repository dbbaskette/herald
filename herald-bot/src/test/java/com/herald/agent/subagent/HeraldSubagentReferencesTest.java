package com.herald.agent.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldSubagentReferencesTest {

    @Test
    void loadsReferencesFromDirectory(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("test-agent.md"),
                """
                ---
                name: test
                description: A test agent
                model: default
                tools: Read, Grep
                ---
                You are a test agent.
                """);

        List<SubagentReference> refs = HeraldSubagentReferences.fromDirectory(tempDir.toString());

        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst().uri()).contains("test-agent");
    }

    @Test
    void returnsEmptyForMissingDirectory() {
        List<SubagentReference> refs = HeraldSubagentReferences.fromDirectory("/nonexistent/path");
        assertThat(refs).isEmpty();
    }

    @Test
    void returnsEmptyForEmptyDirectory(@TempDir Path tempDir) {
        List<SubagentReference> refs = HeraldSubagentReferences.fromDirectory(tempDir.toString());
        assertThat(refs).isEmpty();
    }
}
