package com.herald.agent;

import com.herald.tools.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OptionalToolRegistrationTest {
    @Test void unavailableCliIsAbsentFromNamesAndModelCallbacksEvenIfCallerSuppliesToolBean() {
        HeraldAgentConfig config=new HeraldAgentConfig();
        GwsTools google=mock(GwsTools.class);
        var reminders=mock(RemindersAvailabilityChecker.class);
        assertThat(config.activeToolNames(Optional.empty(),Optional.empty(),Optional.of(google),Optional.empty(),reminders))
                .doesNotContain("gws","cron","reminders","telegram_send");
        var tools=config.buildToolList(mock(HeraldShellDecorator.class),new FileSystemTools(),
                org.springaicommunity.agent.tools.TodoWriteTool.builder().build(),
                mock(org.springaicommunity.agent.tools.AskUserQuestionTool.class),Optional.empty(),Optional.of(google),
                Optional.empty(),reminders,new WebTools(""),Optional.empty(),mock(ValidateSkillTool.class));
        assertThat(tools).doesNotContain(google);
        assertThat(java.util.Arrays.stream(ToolCallbacks.from(tools.toArray())).map(t -> t.getToolDefinition().name()))
                .doesNotContain("gmail_threads_list","calendar_events_list","cron_create");
    }
}
