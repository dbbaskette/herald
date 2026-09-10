package com.herald.tools;

import com.herald.agent.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Browser mutations never use ApprovalGate's legacy web auto-approval branch. */
@Component
@ConditionalOnProperty(name="herald.browser.enabled",havingValue="true")
public class BrowserApproval {
    private final ApprovalGate telegram;
    private final PendingApprovalRegistry registry;
    private final ApprovalPromptHandler web;
    public BrowserApproval(ApprovalGate telegram, PendingApprovalRegistry registry, Optional<ApprovalPromptHandler> web) {
        this.telegram=telegram; this.registry=registry; this.web=web.orElse(null);
    }
    public boolean approve(String conversation,String action) {
        if (ChatChannelContext.get()==ChatChannelContext.Channel.TELEGRAM)
            return "APPROVED".equals(telegram.requestApproval(action.replaceAll("(?s)\\nValue: .*", "\nValue: [redacted; review the intended value in the conversation]")));
        if (!ChatChannelContext.isWeb() || web==null) return false;
        var approval=new PendingApproval(UUID.randomUUID().toString(),"browser",conversation,"WEB",
                "browser_action",null,action,Instant.now(),60);
        var future=registry.register(approval);
        try { web.onApprovalRequired(approval); return Boolean.TRUE.equals(future.get(60,TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { return false; }
        finally { registry.remove(approval.id()); }
    }
}
