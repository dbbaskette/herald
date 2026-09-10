package com.herald.tools;
import com.herald.agent.*;
import java.util.Optional;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class BrowserApprovalTest {
    @AfterEach void clear() { ChatChannelContext.clear(); }
    @Test void webApprovalUsesInboxInsteadOfLegacyAutoApproval() {
        var legacy=mock(ApprovalGate.class); var registry=new PendingApprovalRegistry();
        var approval=new BrowserApproval(legacy,registry,Optional.of(a -> registry.resolve(a.id(),false)));
        ChatChannelContext.set(ChatChannelContext.Channel.WEB,"fixture");
        assertThat(approval.approve("fixture","Submit fixture")).isFalse();
        verifyNoInteractions(legacy); assertThat(registry.listAll()).isEmpty();
        approval=new BrowserApproval(legacy,registry,Optional.of(a -> {
            assertThat(a.diffPreview()).contains("Value: exact fixture value"); registry.resolve(a.id(),true);
        }));
        assertThat(approval.approve("fixture","Type into #name\nValue: exact fixture value")).isTrue();
    }
    @Test void absentAndUnattendedApprovalFailClosed() {
        var approval=new BrowserApproval(mock(ApprovalGate.class),new PendingApprovalRegistry(),Optional.empty());
        ChatChannelContext.set(ChatChannelContext.Channel.SYSTEM,"fixture");
        assertThat(approval.approve("fixture","Click")).isFalse();
        ChatChannelContext.set(ChatChannelContext.Channel.WEB,"fixture");
        assertThat(approval.approve("fixture","Click")).isFalse();
    }
}
