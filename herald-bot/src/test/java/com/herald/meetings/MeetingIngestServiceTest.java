package com.herald.meetings;

import com.herald.agent.*;
import com.herald.api.ChatNotificationsHub;
import com.herald.tools.RemindersTools;
import org.springframework.ai.chat.memory.ChatMemory;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MeetingIngestServiceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;
    AgentService agent=mock(AgentService.class);
    MeetingIngestLedger ledger=mock(MeetingIngestLedger.class);
    MessageSender sender=mock(MessageSender.class);
    RemindersTools reminders=mock(RemindersTools.class);
    MeetingNoteVerifier verifier=mock(MeetingNoteVerifier.class);
    ChatMemory memory=mock(ChatMemory.class);
    MeetingIngestService service;
    MeetingDigest meeting=new MeetingDigest("fixture","fixture","Fixture","2026-08-30",10,"done",List.of(),"Summary",
            List.of(new MeetingDigest.ActionItem("Task","Dan",null),new MeetingDigest.ActionItem("Other","Someone else",null)));
    MeetingIngestLedger.Claim claim=new MeetingIngestLedger.Claim("fixture","token",meeting,null);
    void setup(boolean recap,boolean fanout) {
        service=new MeetingIngestService(agent,ledger,mock(ChatNotificationsHub.class),memory,Optional.of(sender),reminders,verifier,recap,fanout);
        when(ledger.renew(any(),anyLong())).thenReturn(true);
        when(verifier.isSaved(meeting)).thenReturn(true);
        when(agent.chat(anyString(),anyString())).thenReturn("Saved note");
        when(reminders.reminders_create(anyString(),anyString(),isNull(),anyString())).thenReturn("{\"success\":true}");
    }
    @AfterEach void close() { if(service!=null)service.close(); }
    @Test void independentlyDisablesRecapWhileDeliveringOnlyOwnedActions() {
        setup(false,true); assertThat(service.runIngestTurn(claim)).isTrue();
        verifyNoInteractions(sender); verify(reminders).reminders_create(eq("Reminders"),eq("Task"),isNull(),contains("fixture action 0"));
        verify(ledger).finish(claim,null);
    }
    @Test void independentlyDisablesReminders() {
        setup(true,false); assertThat(service.runIngestTurn(claim)).isTrue();
        verifyNoInteractions(reminders); verify(sender).sendMessageOrThrow("Saved note");
    }
    @Test void modelClaimWithoutActualFileEvidenceIsFailure() {
        setup(true,true); when(verifier.isSaved(meeting)).thenReturn(false);
        when(agent.chat(anyString(),anyString())).thenReturn("Saved! [HERALD_MEETING_SAVED]");
        assertThat(service.runIngestTurn(claim)).isFalse();
        verify(ledger).finish(eq(claim),contains("No durable note")); verifyNoInteractions(sender,reminders);
    }
    @Test void downstreamFailureRetriesWithoutRepeatingEnrichmentOrAcknowledgedAction() {
        setup(true,true);
        var resumed=new MeetingIngestLedger.Claim("fixture","token",meeting,"Saved earlier");
        when(ledger.effectDone(resumed,"reminder-0")).thenReturn(true);
        doThrow(new IllegalStateException("offline")).when(sender).sendMessageOrThrow(anyString());
        assertThat(service.runIngestTurn(resumed)).isFalse();
        verifyNoInteractions(agent,reminders); verify(ledger).finish(resumed,"offline");
    }
    @Test void actualTelegramRejectionLeavesRecapUncheckpointedAndJobFailed() {
        var bot=mock(com.pengrad.telegrambot.TelegramBot.class);
        var rejected=mock(com.pengrad.telegrambot.response.SendResponse.class);
        when(rejected.errorCode()).thenReturn(403);
        when(bot.execute(any(com.pengrad.telegrambot.request.SendMessage.class))).thenReturn(rejected);
        var config=new com.herald.config.HeraldConfig(null,
                new com.herald.config.HeraldConfig.Telegram("fixture-token","fixture-chat"),
                null,null,null,null,null,null,null,null);
        var transport=new com.herald.telegram.TelegramSender(bot,config,new com.herald.telegram.MessageFormatter());
        service=new MeetingIngestService(agent,ledger,mock(ChatNotificationsHub.class),memory,
                Optional.of(transport),reminders,verifier,true,false);
        when(ledger.renew(any(),anyLong())).thenReturn(true);
        var resumed=new MeetingIngestLedger.Claim("fixture","token",meeting,"Saved earlier");
        assertThat(service.runIngestTurn(resumed)).isFalse();
        verify(ledger,never()).recordEffect(resumed,"recap");
        verify(ledger).finish(eq(resumed),contains("did not acknowledge"));
    }

    @Test void succeedsOnlyAfterMockAgentWritesRealFixtureNote() throws Exception {
        var config=mock(com.herald.config.HeraldConfig.class);
        when(config.memoriesDir()).thenReturn(dir.toString()); when(config.obsidianVaultPath()).thenReturn("");
        service=new MeetingIngestService(agent,ledger,mock(ChatNotificationsHub.class),memory,Optional.of(sender),reminders,
                new MeetingNoteVerifier(config),false,false);
        when(ledger.renew(any(),anyLong())).thenReturn(true);
        when(agent.chat(anyString(),anyString())).thenAnswer(invocation -> {
            java.nio.file.Files.writeString(dir.resolve("fixture.md"),"Source: fixture\nSummary");
            return "Saved fixture note";
        });
        assertThat(service.runIngestTurn(claim)).isTrue();
        verify(ledger).checkpoint(claim,"Saved fixture note");
    }
    @Test void failedCleanupDoesNotChangeSuccessfulOutcome() {
        setup(false,false); doThrow(new IllegalStateException("cleanup")).when(memory).clear(anyString());
        assertThat(service.runIngestTurn(claim)).isTrue(); assertThat(ChatChannelContext.get()).isNull();
    }
}
