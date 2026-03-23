package com.herald.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VaultIndexerTest {

    @TempDir
    Path tempDir;
    private Path hashesFile;

    @BeforeEach
    void setUp() {
        hashesFile = tempDir.resolve("hashes.json");
    }

    @Test
    void detectsNewFile() throws IOException {
        Path vaultDir = tempDir.resolve("vault");
        Files.createDirectories(vaultDir);
        Files.writeString(vaultDir.resolve("note.md"), "# Hello\n\nWorld");
        Map<String, String> oldHashes = Map.of();
        Map<String, String> newHashes = VaultIndexer.computeFileHashes(vaultDir);
        assertThat(newHashes).hasSize(1).containsKey("note.md");
        assertThat(VaultIndexer.findChanged(oldHashes, newHashes)).containsExactly("note.md");
        assertThat(VaultIndexer.findDeleted(oldHashes, newHashes)).isEmpty();
    }

    @Test
    void detectsModifiedFile() throws IOException {
        Path vaultDir = tempDir.resolve("vault");
        Files.createDirectories(vaultDir);
        Files.writeString(vaultDir.resolve("note.md"), "# Hello\n\nWorld");
        Map<String, String> oldHashes = VaultIndexer.computeFileHashes(vaultDir);
        Files.writeString(vaultDir.resolve("note.md"), "# Hello\n\nUpdated");
        Map<String, String> newHashes = VaultIndexer.computeFileHashes(vaultDir);
        assertThat(VaultIndexer.findChanged(oldHashes, newHashes)).containsExactly("note.md");
    }

    @Test
    void detectsDeletedFile() throws IOException {
        Path vaultDir = tempDir.resolve("vault");
        Files.createDirectories(vaultDir);
        Files.writeString(vaultDir.resolve("note.md"), "content");
        Map<String, String> oldHashes = VaultIndexer.computeFileHashes(vaultDir);
        Files.delete(vaultDir.resolve("note.md"));
        Map<String, String> newHashes = VaultIndexer.computeFileHashes(vaultDir);
        assertThat(VaultIndexer.findDeleted(oldHashes, newHashes)).containsExactly("note.md");
        assertThat(VaultIndexer.findChanged(oldHashes, newHashes)).isEmpty();
    }

    @Test
    void ignoresNonMarkdownFiles() throws IOException {
        Path vaultDir = tempDir.resolve("vault");
        Files.createDirectories(vaultDir);
        Files.writeString(vaultDir.resolve("note.md"), "content");
        Files.writeString(vaultDir.resolve("image.png"), "binary");
        Files.writeString(vaultDir.resolve(".DS_Store"), "meta");
        Map<String, String> hashes = VaultIndexer.computeFileHashes(vaultDir);
        assertThat(hashes).hasSize(1).containsKey("note.md");
    }

    @Test
    void hashPersistenceRoundTrip() throws IOException {
        Map<String, String> hashes = Map.of("a.md", "abc123", "b.md", "def456");
        VaultIndexer.saveHashes(hashesFile, hashes);
        Map<String, String> loaded = VaultIndexer.loadHashes(hashesFile);
        assertThat(loaded).isEqualTo(hashes);
    }

    @Test
    void loadHashesReturnsEmptyWhenFileMissing() {
        Map<String, String> loaded = VaultIndexer.loadHashes(tempDir.resolve("nonexistent.json"));
        assertThat(loaded).isEmpty();
    }
}
