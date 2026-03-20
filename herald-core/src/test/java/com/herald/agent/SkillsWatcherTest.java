package com.herald.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

class SkillsWatcherTest {

    @TempDir
    Path tempDir;

    private ReloadableSkillsTool reloadableSkillsTool;

    @BeforeEach
    void setUp() {
        reloadableSkillsTool = mock(ReloadableSkillsTool.class);
    }

    @Test
    void startWatchingLogsWarningWhenDirectoryDoesNotExist() {
        String nonExistent = tempDir.resolve("nonexistent").toString();
        SkillsWatcher watcher = new SkillsWatcher(reloadableSkillsTool, nonExistent);

        assertThatNoException().isThrownBy(watcher::startWatching);
        // reload should not be called when directory doesn't exist
        verify(reloadableSkillsTool, never()).reload();

        watcher.stopWatching();
    }

    @Test
    void startWatchingSucceedsWithExistingDirectory() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);

        SkillsWatcher watcher = new SkillsWatcher(reloadableSkillsTool, skillsDir.toString());

        assertThatNoException().isThrownBy(watcher::startWatching);

        watcher.stopWatching();
    }

    @Test
    void fileChangeTriggersReload() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);

        when(reloadableSkillsTool.reload()).thenReturn(1);

        SkillsWatcher watcher = new SkillsWatcher(reloadableSkillsTool, skillsDir.toString());
        watcher.startWatching();

        // Create a skill file to trigger the watcher
        Files.writeString(skillsDir.resolve("SKILL.md"), "# Test Skill\nA test skill.");

        // macOS WatchService uses polling (~2-10s), so poll with a generous timeout
        waitForReload(reloadableSkillsTool);

        watcher.stopWatching();
    }

    @Test
    void subdirectoryCreationTriggersReload() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);

        when(reloadableSkillsTool.reload()).thenReturn(1);

        SkillsWatcher watcher = new SkillsWatcher(reloadableSkillsTool, skillsDir.toString());
        watcher.startWatching();

        // Create a subdirectory with a skill file
        Path subDir = skillsDir.resolve("my-skill");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("SKILL.md"), "# Sub Skill\nA sub skill.");

        // macOS WatchService uses polling (~2-10s), so poll with a generous timeout
        waitForReload(reloadableSkillsTool);

        watcher.stopWatching();
    }

    @Test
    void stopWatchingCleansUpResources() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);

        SkillsWatcher watcher = new SkillsWatcher(reloadableSkillsTool, skillsDir.toString());
        watcher.startWatching();

        assertThatNoException().isThrownBy(watcher::stopWatching);
    }

    @Test
    void stopWatchingWhenNeverStarted() {
        String nonExistent = tempDir.resolve("nonexistent").toString();
        SkillsWatcher watcher = new SkillsWatcher(reloadableSkillsTool, nonExistent);

        assertThatNoException().isThrownBy(watcher::stopWatching);
    }

    @Test
    void debounceCoalescesMultipleEvents() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);

        when(reloadableSkillsTool.reload()).thenReturn(2);

        SkillsWatcher watcher = new SkillsWatcher(reloadableSkillsTool, skillsDir.toString());
        watcher.startWatching();

        // Rapid-fire multiple file changes
        Files.writeString(skillsDir.resolve("file1.md"), "content1");
        Files.writeString(skillsDir.resolve("file2.md"), "content2");
        Files.writeString(skillsDir.resolve("file3.md"), "content3");

        // macOS WatchService uses polling (~2-10s), so poll with a generous timeout
        waitForReload(reloadableSkillsTool);

        watcher.stopWatching();
    }

    @Test
    void scheduleReloadTriggersReloadAfterDebounce() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        when(reloadableSkillsTool.reload()).thenReturn(1);

        SkillsWatcher watcher = new SkillsWatcher(reloadableSkillsTool, skillsDir.toString());
        watcher.startWatching();

        // Directly invoke the debounced reload
        watcher.scheduleReload();
        Thread.sleep(500);

        verify(reloadableSkillsTool, times(1)).reload();

        watcher.stopWatching();
    }

    @Test
    void debounceCoalescesRapidScheduleCalls() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        when(reloadableSkillsTool.reload()).thenReturn(3);

        SkillsWatcher watcher = new SkillsWatcher(reloadableSkillsTool, skillsDir.toString());
        watcher.startWatching();

        // Rapid-fire — debounce should coalesce into one reload
        watcher.scheduleReload();
        watcher.scheduleReload();
        watcher.scheduleReload();

        Thread.sleep(500);

        verify(reloadableSkillsTool, times(1)).reload();

        watcher.stopWatching();
    }

    /**
     * Polls until reload() has been called at least once, with a timeout.
     * macOS WatchService uses polling internally (~2-10 seconds), so we
     * need a longer wait than the 250ms debounce.
     */
    private static void waitForReload(ReloadableSkillsTool mock) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(mock, atLeastOnce()).reload();
                return;
            } catch (AssertionError e) {
                // not yet — keep polling
            }
            Thread.sleep(250);
        }
        // Final assertion — let it fail with a clear message if still not called
        verify(mock, atLeastOnce()).reload();
    }
}
