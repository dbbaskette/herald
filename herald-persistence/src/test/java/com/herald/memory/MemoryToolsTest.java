package com.herald.memory;

import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryToolsTest {

    @TempDir
    Path tempDir;

    private MemoryTools tools;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("test.db").toString();
        DataSource dataSource = DataSourceBuilder.create()
                .driverClassName("org.sqlite.JDBC")
                .url("jdbc:sqlite:" + dbPath)
                .build();

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        populator.setContinueOnError(true);

        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(populator);
        initializer.afterPropertiesSet();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        MemoryRepository repository = new MemoryRepository(jdbcTemplate);
        tools = new MemoryTools(repository);
    }

    @Test
    void memorySetReturnsConfirmation() {
        String result = tools.memory_set("project", "Herald bot");
        assertThat(result).isEqualTo("Stored memory: project = Herald bot");
    }

    @Test
    void memoryGetReturnsStoredValue() {
        tools.memory_set("project", "Herald bot");
        assertThat(tools.memory_get("project")).isEqualTo("project = Herald bot");
    }

    @Test
    void memoryGetReturnsMessageForMissingKey() {
        assertThat(tools.memory_get("missing")).isEqualTo("No memory found for key: missing");
    }

    @Test
    void memoryListReturnsAllEntries() {
        tools.memory_set("name", "Dan");
        tools.memory_set("project", "Herald");

        String result = tools.memory_list();
        assertThat(result).contains("- **name**: Dan")
                .contains("- **project**: Herald");
    }

    @Test
    void memoryListReturnsMessageWhenEmpty() {
        assertThat(tools.memory_list()).isEqualTo("No memories stored.");
    }

    @Test
    void memoryDeleteReturnsConfirmation() {
        tools.memory_set("project", "Herald");
        assertThat(tools.memory_delete("project")).isEqualTo("Deleted memory: project");
    }

    @Test
    void memoryDeleteReturnsMessageForMissingKey() {
        assertThat(tools.memory_delete("missing")).isEqualTo("No memory found for key: missing");
    }

    @Test
    void formatForSystemPromptReturnsMarkdown() {
        tools.memory_set("name", "Dan");
        tools.memory_set("project", "Herald");

        String prompt = tools.formatForSystemPrompt();
        assertThat(prompt).startsWith("## Known Facts\n")
                .contains("- **name**: Dan")
                .contains("- **project**: Herald");
    }

    @Test
    void formatForSystemPromptReturnsEmptyWhenNoEntries() {
        assertThat(tools.formatForSystemPrompt()).isEmpty();
    }

    @Test
    void setRejectsNullKey() {
        assertThatThrownBy(() -> tools.memory_set(null, "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setRejectsBlankKey() {
        assertThatThrownBy(() -> tools.memory_set("  ", "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setRejectsNullValue() {
        assertThatThrownBy(() -> tools.memory_set("key", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setRejectsBlankValue() {
        assertThatThrownBy(() -> tools.memory_set("key", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void specialCharactersInValuesAreStoredAndRetrieved() {
        tools.memory_set("markdown", "**bold** and *italic* and `code`");
        assertThat(tools.memory_get("markdown")).contains("**bold** and *italic* and `code`");
    }

    @Test
    void newlinesInValuesDoNotCorruptList() {
        tools.memory_set("multiline", "line1\nline2\nline3");
        String list = tools.memory_list();
        assertThat(list).contains("- **multiline**:");
    }

    @Test
    void formatForSystemPromptSanitizesNewlines() {
        tools.memory_set("inject", "value\n## Injected Header\n- fake entry");
        String prompt = tools.formatForSystemPrompt();
        // Newlines should be collapsed — injected content cannot create new markdown lines
        assertThat(prompt.lines().count()).isEqualTo(2); // header + one entry only
        assertThat(prompt).contains("value ## Injected Header - fake entry");
    }

    @Test
    void sanitizeTruncatesLongValues() {
        String longValue = "a".repeat(600);
        String sanitized = MemoryTools.sanitize(longValue);
        assertThat(sanitized).hasSize(501); // 500 + ellipsis character
        assertThat(sanitized).endsWith("…");
    }

    @Test
    void sanitizeHandlesNullInput() {
        assertThat(MemoryTools.sanitize(null)).isEmpty();
    }
}
