package com.herald.ui;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.herald.ui.config.HeraldUiConfig;

class SkillValidationServiceTest {
    @TempDir Path root;
    SkillValidationService service(Path vault) {
        var capabilities = mock(SkillCapabilities.class);
        when(capabilities.current()).thenReturn(new SkillCapabilities.Snapshot("available", Set.of("shell", "skills")));
        return new SkillValidationService(new HeraldUiConfig("unused", root.toString(), ""), capabilities,
                Path.of("..").toAbsolutePath().normalize().toString(), vault == null ? "" : vault.toString());
    }
    String valid(String body) { return "---\nname: test\ndescription: >\n  A folded\n  description.\n---\n" + body; }
    @Test void parsesExactFoldedPreviewAndReportsDuplicateLine() {
        var valid = service(null).validate("test", valid("hello"), false);
        assertThat(valid.valid()).isTrue();
        assertThat(valid.description()).isEqualTo("A folded description.");
        var duplicate = service(null).validate("test", "---\nname: one\nname: two\ndescription: hi\n---", false);
        assertThat(duplicate.valid()).isFalse();
        assertThat(duplicate.diagnostics().getFirst().line()).isEqualTo(3);
    }
    @Test void rejectsMissingTypedFieldsUnsafeYamlAndMissingDelimiter() {
        for (String text : new String[]{"---\nname: test\n---", "---\nname: [one]\ndescription: hi\n---",
                "---\nname: !!java.net.URL [https://example.com]\ndescription: hi\n---", "---\nname: test"}) {
            assertThat(service(null).validate("test", text, false).valid()).isFalse();
        }
    }
    @Test void checksConcreteReferencesIncludingCodeButWarningsDoNotBlock() throws Exception {
        Path vault = Files.createDirectory(root.resolve("vault"));
        Files.writeString(vault.resolve("known.md"), "hi");
        Files.createDirectories(root.resolve("test/examples"));
        Files.writeString(root.resolve("test/examples/known.md"), "hi");
        var result = service(vault).validate("test", valid("Use the `missing` tool.\nSee examples/known.md and examples/missing.md.\n```markdown\n[[known]] [[missing|label]] [[<placeholder>]]\n```"), true);
        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics()).extracting(SkillValidationService.Diagnostic::code)
                .containsExactly("unavailable-tool", "missing-file", "missing-wikilink");
    }
    @Test void offlineToolsAreUncheckedRatherThanIncorrectlyMissing() {
        var capabilities = mock(SkillCapabilities.class);
        when(capabilities.current()).thenReturn(new SkillCapabilities.Snapshot("unavailable", Set.of()));
        var service = new SkillValidationService(new HeraldUiConfig("unused", root.toString(), ""), capabilities, "", "");
        var result = service.validate("test", valid("Use the `shell` tool."), true);
        assertThat(result.capabilityStatus()).isEqualTo("unavailable");
        assertThat(result.diagnostics()).isEmpty();
    }
    @Test void referencesNeverFollowTraversalOrSymlinks() throws Exception {
        Path safe = Files.createDirectory(root.resolve("safe"));
        Path outside = Files.writeString(root.resolve("secret.md"), "secret");
        Files.createSymbolicLink(safe.resolve("link.md"), outside);
        assertThat(SkillValidationService.exists(safe, "../secret.md")).isFalse();
        assertThat(SkillValidationService.exists(safe, "link.md")).isFalse();
    }
    @Test void shippedSkillsHaveNoFalseWarningsWithoutOptionalVault() throws Exception {
        for (String name : new String[]{"weather", "github", "obsidian"}) {
            String content = Files.readString(Path.of("../skills", name, "SKILL.md"));
            var result = service(null).validate(name, content, true);
            assertThat(result.valid()).as(name).isTrue();
            assertThat(result.diagnostics()).as(name).isEmpty();
            Path vault = Files.createDirectories(root.resolve("empty-vault"));
            assertThat(service(vault).validate(name, content, true).diagnostics()).as(name + " configured vault").isEmpty();
        }
    }
}
