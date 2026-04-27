package com.herald.agent;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryApprovalPolicyTest {

    @Test
    void defaultsGateConceptEntitySourceAndDestructive() {
        MemoryApprovalPolicy p = MemoryApprovalPolicy.defaults();

        assertThat(p.resolveMode("memoryStrReplace", "concept")).isEqualTo(MemoryApprovalPolicy.Mode.CONFIRM_DIFF);
        assertThat(p.resolveMode("memoryStrReplace", "entity")).isEqualTo(MemoryApprovalPolicy.Mode.CONFIRM_DIFF);
        assertThat(p.resolveMode("memoryStrReplace", "source")).isEqualTo(MemoryApprovalPolicy.Mode.CONFIRM_DIFF);
    }

    @Test
    void defaultsAutoApplyLowStakesTypes() {
        MemoryApprovalPolicy p = MemoryApprovalPolicy.defaults();

        assertThat(p.resolveMode("memoryStrReplace", "user")).isEqualTo(MemoryApprovalPolicy.Mode.AUTO);
        assertThat(p.resolveMode("memoryStrReplace", "feedback")).isEqualTo(MemoryApprovalPolicy.Mode.AUTO);
        assertThat(p.resolveMode("memoryStrReplace", "project")).isEqualTo(MemoryApprovalPolicy.Mode.AUTO);
        assertThat(p.resolveMode("memoryStrReplace", "reference")).isEqualTo(MemoryApprovalPolicy.Mode.AUTO);
    }

    @Test
    void deleteOverridesTypePolicy() {
        MemoryApprovalPolicy p = MemoryApprovalPolicy.defaults();

        // Even on a low-stakes type, delete still gates by default.
        assertThat(p.resolveMode("memoryDelete", "user")).isEqualTo(MemoryApprovalPolicy.Mode.CONFIRM_DIFF);
        assertThat(p.resolveMode("memoryRename", "feedback")).isEqualTo(MemoryApprovalPolicy.Mode.CONFIRM_DIFF);
    }

    @Test
    void unknownTypeUsesDefaultMode() {
        MemoryApprovalPolicy p = new MemoryApprovalPolicy(
                Map.of(), MemoryApprovalPolicy.Mode.AUTO, MemoryApprovalPolicy.Mode.AUTO,
                MemoryApprovalPolicy.Mode.CONFIRM_DIFF, 60);

        assertThat(p.resolveMode("memoryStrReplace", "weirdo")).isEqualTo(MemoryApprovalPolicy.Mode.CONFIRM_DIFF);
        assertThat(p.resolveMode("memoryStrReplace", null)).isEqualTo(MemoryApprovalPolicy.Mode.CONFIRM_DIFF);
    }

    @Test
    void disabledPolicyAlwaysAutoApplies() {
        MemoryApprovalPolicy p = MemoryApprovalPolicy.disabled();

        assertThat(p.resolveMode("memoryStrReplace", "concept")).isEqualTo(MemoryApprovalPolicy.Mode.AUTO);
        assertThat(p.resolveMode("memoryDelete", "concept")).isEqualTo(MemoryApprovalPolicy.Mode.AUTO);
    }

    @Test
    void parseAcceptsKebabAndSnakeCase() {
        assertThat(MemoryApprovalPolicy.Mode.parse("auto")).isEqualTo(MemoryApprovalPolicy.Mode.AUTO);
        assertThat(MemoryApprovalPolicy.Mode.parse("confirm-diff")).isEqualTo(MemoryApprovalPolicy.Mode.CONFIRM_DIFF);
        assertThat(MemoryApprovalPolicy.Mode.parse("confirm_diff")).isEqualTo(MemoryApprovalPolicy.Mode.CONFIRM_DIFF);
        assertThat(MemoryApprovalPolicy.Mode.parse("CONFIRM-DIFF")).isEqualTo(MemoryApprovalPolicy.Mode.CONFIRM_DIFF);
    }

    @Test
    void parseReturnsNullForUnknown() {
        assertThat(MemoryApprovalPolicy.Mode.parse(null)).isNull();
        assertThat(MemoryApprovalPolicy.Mode.parse("yolo")).isNull();
    }
}
