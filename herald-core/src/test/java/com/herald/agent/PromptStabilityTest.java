package com.herald.agent;

import com.herald.config.HeraldConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression suite for issue #313 — asserts that every component contributing
 * to Herald's system prompt / tool catalog / per-request payload produces
 * byte-for-byte identical output across successive calls with the same inputs.
 *
 * <p>Why: Anthropic's prompt cache matches on the <em>byte-identical</em>
 * prefix of consecutive requests. A single reordered element invalidates the
 * cache. These tests catch ordering regressions before they land in a cost
 * report.</p>
 *
 * <p>Covered today: skills ({@link ReloadableSkillsTool}) and A2A agents
 * ({@link HeraldConfig#a2aAgents()}). Add a case here whenever you introduce
 * a new list-to-prompt pipeline.</p>
 */
class PromptStabilityTest {

    @TempDir
    Path skillsRoot;

    @Test
    void reloadableSkillsToolProducesByteIdenticalOutputAcrossReloads() throws IOException {
        // Create skills in a pseudo-random order; the filesystem walk may visit
        // them in any order depending on the platform, but ReloadableSkillsTool
        // should normalize.
        String[] names = {"charlie", "alpha", "echo", "bravo", "delta"};
        for (String name : names) {
            Path dir = skillsRoot.resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), """
                    ---
                    name: %s
                    description: test
                    ---

                    body for %s
                    """.formatted(name, name), StandardCharsets.UTF_8);
        }

        var tool1 = new ReloadableSkillsTool(skillsRoot.toString());
        List<String> order1 = tool1.getSkills().stream().map(s -> s.name()).toList();

        var tool2 = new ReloadableSkillsTool(skillsRoot.toString());
        List<String> order2 = tool2.getSkills().stream().map(s -> s.name()).toList();

        assertThat(order1).isEqualTo(order2);
        assertThat(order1).containsExactly("alpha", "bravo", "charlie", "delta", "echo");
    }

    @Test
    void reloadableSkillsToolReloadIsIdempotent() throws IOException {
        Path skillDir = skillsRoot.resolve("alpha");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: alpha
                description: test
                ---
                """);

        var tool = new ReloadableSkillsTool(skillsRoot.toString());
        List<String> before = tool.getSkills().stream().map(s -> s.name()).toList();
        tool.reload();
        tool.reload();
        List<String> after = tool.getSkills().stream().map(s -> s.name()).toList();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void a2aAgentsReturnedInDeterministicOrder() {
        // Feed in reverse-alphabetical order to prove sort-not-insertion governs.
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null,
                new HeraldConfig.A2a(List.of(
                        new HeraldConfig.A2aAgent("zebra", "http://z", Map.of()),
                        new HeraldConfig.A2aAgent("Alpha", "http://a", Map.of()),
                        new HeraldConfig.A2aAgent("mango", "http://m", Map.of()),
                        new HeraldConfig.A2aAgent("bravo", "http://b", Map.of()))));

        List<String> names1 = config.a2aAgents().stream().map(HeraldConfig.A2aAgent::name).toList();
        List<String> names2 = config.a2aAgents().stream().map(HeraldConfig.A2aAgent::name).toList();

        assertThat(names1).isEqualTo(names2);
        assertThat(names1).containsExactly("Alpha", "bravo", "mango", "zebra");
    }

    @Test
    void a2aAgentsHandlesEmptyAndNullGracefully() {
        HeraldConfig nullA2a = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);
        assertThat(nullA2a.a2aAgents()).isEmpty();

        HeraldConfig emptyAgents = new HeraldConfig(null, null, null, null, null, null, null, null, null, null,
                new HeraldConfig.A2a(List.of()));
        assertThat(emptyAgents.a2aAgents()).isEmpty();
    }
}
