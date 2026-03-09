package com.herald.telegram;

import com.herald.memory.MemoryTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CommandHandlerTest {

    private MemoryTools memoryTools;
    private ChatMemory chatMemory;
    private TelegramSender sender;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        memoryTools = mock(MemoryTools.class);
        chatMemory = mock(ChatMemory.class);
        sender = mock(TelegramSender.class);
        handler = new CommandHandler(memoryTools, chatMemory, sender, "claude-sonnet-4-5");
    }

    // --- handle() routing ---

    @Test
    void handleReturnsFalseForNonCommand() {
        assertThat(handler.handle("hello")).isFalse();
        verifyNoInteractions(sender);
    }

    @Test
    void handleReturnsFalseForNull() {
        assertThat(handler.handle(null)).isFalse();
        verifyNoInteractions(sender);
    }

    @Test
    void handleReturnsTrueForSlashCommand() {
        assertThat(handler.handle("/help")).isTrue();
        verify(sender).sendMessage(anyString());
    }

    // --- /help ---

    @Test
    void helpReturnsFormattedCommandList() {
        handler.handle("/help");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("/help") && msg.contains("/status")
                        && msg.contains("/reset") && msg.contains("/memory")));
    }

    @Test
    void helpIsCaseInsensitive() {
        handler.handle("/HELP");
        verify(sender).sendMessage(argThat(msg -> msg.contains("/help")));
    }

    // --- /status ---

    @Test
    void statusShowsUptimeAndModel() {
        when(memoryTools.count()).thenReturn(3);
        handler.handle("/status");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("claude-sonnet-4-5") && msg.contains("3")
                        && msg.contains("Uptime")));
    }

    // --- /reset ---

    @Test
    void resetClearsConversationHistory() {
        handler.handle("/reset");
        verify(chatMemory).clear("default");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("cleared") && msg.contains("Memory entries are preserved")));
    }

    // --- /debug ---

    @Test
    void debugShowsMemoryCountAndTools() {
        when(memoryTools.count()).thenReturn(7);
        handler.handle("/debug");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("7") && msg.contains("Active tools")));
    }

    // --- /memory list ---

    @Test
    void memoryListDisplaysEntries() {
        when(memoryTools.memory_list()).thenReturn("- **name**: Dan\n- **role**: engineer");
        handler.handle("/memory list");
        verify(memoryTools).memory_list();
        verify(sender).sendMessage("- **name**: Dan\n- **role**: engineer");
    }

    // --- /memory set ---

    @Test
    void memorySetStoresValue() {
        when(memoryTools.memory_set("name", "Dan")).thenReturn("Stored memory: name = Dan");
        handler.handle("/memory set name Dan");
        verify(memoryTools).memory_set("name", "Dan");
        verify(sender).sendMessage("Stored memory: name = Dan");
    }

    @Test
    void memorySetWithMultiWordValue() {
        when(memoryTools.memory_set("project", "herald bot")).thenReturn("Stored memory: project = herald bot");
        handler.handle("/memory set project herald bot");
        verify(memoryTools).memory_set("project", "herald bot");
    }

    @Test
    void memorySetWithMissingValueShowsUsage() {
        handler.handle("/memory set name");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
        verify(memoryTools, never()).memory_set(any(), any());
    }

    // --- /memory clear ---

    @Test
    void memoryClearDeletesAllEntries() {
        handler.handle("/memory clear");
        verify(memoryTools).clearAll();
        verify(sender).sendMessage(argThat(msg -> msg.contains("cleared")));
    }

    // --- /memory (no subcommand) ---

    @Test
    void memoryWithNoSubcommandShowsUsage() {
        handler.handle("/memory");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Usage")));
    }

    // --- /memory unknown ---

    @Test
    void memoryUnknownSubcommandShowsUsage() {
        handler.handle("/memory foo");
        verify(sender).sendMessage(argThat(msg -> msg.contains("Unknown memory subcommand")));
    }

    // --- unknown command ---

    @Test
    void unknownCommandReturnsHelpfulError() {
        handler.handle("/foo");
        verify(sender).sendMessage(argThat(msg ->
                msg.contains("Unknown command") && msg.contains("/help")));
    }

    // --- formatUptime ---

    @Test
    void formatUptimeMinutesOnly() {
        assertThat(CommandHandler.formatUptime(300_000)).isEqualTo("5m");
    }

    @Test
    void formatUptimeHoursAndMinutes() {
        assertThat(CommandHandler.formatUptime(7_500_000)).isEqualTo("2h 5m");
    }

    @Test
    void formatUptimeDaysHoursMinutes() {
        assertThat(CommandHandler.formatUptime(90_300_000)).isEqualTo("1d 1h 5m");
    }
}
