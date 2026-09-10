package com.herald.agent;

import java.util.List;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

public final class ExecutionAdvisors {
    private ExecutionAdvisors() {}
    public static List<Advisor> create(ExecutionLimits limits, Runnable budgetCheck) {
        return List.of(new ExecutionBoundaryAdvisor(limits),
                ToolCallingAdvisor.builder().advisorOrder(Ordered.LOWEST_PRECEDENCE - 10)
                        .toolCallingManager(new ExecutionToolCallingManager()).build(),
                new ExecutionStepAdvisor(budgetCheck));
    }
}
