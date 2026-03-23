# Vault Vector Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add semantic search over the Obsidian cold memory vault using local Nomic embeddings via LM Studio and Spring AI's SimpleVectorStore, plus lower ChatArchivalJob thresholds so short conversations actually get persisted.

**Architecture:** An `OpenAiEmbeddingModel` bean points at LM Studio's `/v1/embeddings` endpoint. A `SimpleVectorStore` persists to JSON on disk. A `VaultIndexer` service scans/watches the Obsidian vault for markdown files, chunks them adaptively, and indexes them. A `VaultSearchAdvisor` auto-injects top-2 relevant results per turn. A `VaultSearchTools` class exposes deeper search and reindex as agent tools. All components are conditional — Herald works without LM Studio.

**Tech Stack:** Spring AI 2.0.0-SNAPSHOT (`OpenAiEmbeddingModel`, `SimpleVectorStore`), `io.methvin:directory-watcher` for macOS FSEvents, Java 21.

**Spec:** `docs/superpowers/specs/2026-03-23-vault-vector-store-design.md`

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `herald-core/pom.xml` | Modify | No changes needed — `spring-ai-starter-model-openai` already present |
| `herald-persistence/pom.xml` | Modify | Add `spring-ai-vector-store` + `directory-watcher` dependencies |
| `herald-core/src/main/java/com/herald/config/HeraldConfig.java` | Modify | Add `Vault` and `Archival` config records + convenience methods |
| `herald-core/src/main/java/com/herald/agent/ModelProviderConfig.java` | Modify | Add `lmstudioEmbeddingModel` bean |
| `herald-persistence/src/main/java/com/herald/memory/VaultVectorStoreConfig.java` | Create | `SimpleVectorStore` bean with disk persistence |
| `herald-persistence/src/main/java/com/herald/memory/MarkdownChunker.java` | Create | Adaptive chunking: whole-file or heading-split |
| `herald-persistence/src/main/java/com/herald/memory/VaultIndexer.java` | Create | Startup indexing, file watcher, hash tracking, reindex |
| `herald-persistence/src/main/java/com/herald/memory/VaultSearchTools.java` | Create | `vault_search` and `vault_reindex` @Tool methods |
| `herald-persistence/src/main/java/com/herald/agent/VaultSearchAdvisor.java` | Create | Auto-inject top-2 vault context per turn |
| `herald-persistence/src/main/java/com/herald/memory/ChatArchivalJob.java` | Modify | Lower thresholds, add idle detection, 15min schedule |
| `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` | Modify | Wire VaultSearchAdvisor + VaultSearchTools |
| `herald-bot/src/main/resources/application.yaml` | Modify | Add `herald.vault.*` and `herald.archival.*` config |
| `herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md` | Modify | Document vault-context and vault_search |
| `herald-persistence/src/test/java/com/herald/memory/MarkdownChunkerTest.java` | Create | Unit tests for chunking |
| `herald-persistence/src/test/java/com/herald/memory/VaultIndexerTest.java` | Create | Unit tests for hash tracking + change detection |
| `herald-persistence/src/test/java/com/herald/memory/ChatArchivalJobTest.java` | Create | Unit tests for new archival triggers |

---

## Task 1: Dependencies and Configuration Records

**Files:**
- Modify: `herald-persistence/pom.xml:17-61`
- Modify: `herald-core/src/main/java/com/herald/config/HeraldConfig.java:1-105`
- Modify: `herald-bot/src/main/resources/application.yaml:28-78`

- [ ] **Step 1: Add dependencies to herald-persistence pom.xml**

Add after the existing `<!-- SQLite -->` section (line 53):

```xml
<!-- Vector Store -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-vector-store</artifactId>
</dependency>

<!-- Native file watching (macOS FSEvents) -->
<dependency>
    <groupId>io.methvin</groupId>
    <artifactId>directory-watcher</artifactId>
    <version>0.18.0</version>
</dependency>
```

- [ ] **Step 2: Add Vault and Archival records to HeraldConfig.java**

Add after the `Obsidian` record (line 73):

```java
public record Vault(
    String vectorStorePath,
    String indexHashesPath,
    Boolean autoIndexOnStartup,
    Boolean fileWatcherEnabled,
    VaultSearch search,
    VaultChunking chunking
) {
    public record VaultSearch(Integer autoTopK, Integer toolDefaultTopK, Double minSimilarity) {}
    public record VaultChunking(Integer smallFileThreshold, Integer maxChunkSize) {}
}

public record Archival(
    Integer messageThreshold,
    Integer idleTimeoutMinutes,
    Integer minMessagesForIdle,
    Integer keepRecentMessages
) {}
```

Add `Vault vault, Archival archival` to the main record parameter list (line 6-7).

Add convenience methods after `cronTimezone()` (after line 97):

```java
public String vaultVectorStorePath() {
    return vault != null && vault.vectorStorePath() != null
        ? vault.vectorStorePath() : "~/.herald/vector-store.json";
}

public String vaultIndexHashesPath() {
    return vault != null && vault.indexHashesPath() != null
        ? vault.indexHashesPath() : "~/.herald/vault-index-hashes.json";
}

public boolean vaultAutoIndexOnStartup() {
    return vault == null || vault.autoIndexOnStartup() == null || vault.autoIndexOnStartup();
}

public boolean vaultFileWatcherEnabled() {
    return vault == null || vault.fileWatcherEnabled() == null || vault.fileWatcherEnabled();
}

public int vaultAutoTopK() {
    return vault != null && vault.search() != null && vault.search().autoTopK() != null
        ? vault.search().autoTopK() : 2;
}

public int vaultToolDefaultTopK() {
    return vault != null && vault.search() != null && vault.search().toolDefaultTopK() != null
        ? vault.search().toolDefaultTopK() : 5;
}

public double vaultMinSimilarity() {
    return vault != null && vault.search() != null && vault.search().minSimilarity() != null
        ? vault.search().minSimilarity() : 0.7;
}

public int vaultSmallFileThreshold() {
    return vault != null && vault.chunking() != null && vault.chunking().smallFileThreshold() != null
        ? vault.chunking().smallFileThreshold() : 500;
}

public int vaultMaxChunkSize() {
    return vault != null && vault.chunking() != null && vault.chunking().maxChunkSize() != null
        ? vault.chunking().maxChunkSize() : 1000;
}

public int archivalMessageThreshold() {
    return archival != null && archival.messageThreshold() != null
        ? archival.messageThreshold() : 5;
}

public int archivalIdleTimeoutMinutes() {
    return archival != null && archival.idleTimeoutMinutes() != null
        ? archival.idleTimeoutMinutes() : 30;
}

public int archivalMinMessagesForIdle() {
    return archival != null && archival.minMessagesForIdle() != null
        ? archival.minMessagesForIdle() : 2;
}

public int archivalKeepRecentMessages() {
    return archival != null && archival.keepRecentMessages() != null
        ? archival.keepRecentMessages() : 5;
}
```

- [ ] **Step 3: Add configuration to application.yaml**

Add after `herald.obsidian` section (after line 76):

```yaml
  vault:
    vector-store-path: ~/.herald/vector-store.json
    index-hashes-path: ~/.herald/vault-index-hashes.json
    auto-index-on-startup: true
    file-watcher-enabled: true
    search:
      auto-top-k: 2
      tool-default-top-k: 5
      min-similarity: 0.7
    chunking:
      small-file-threshold: 500
      max-chunk-size: 1000
  archival:
    message-threshold: 5
    idle-timeout-minutes: 30
    min-messages-for-idle: 2
    keep-recent-messages: 5
```

- [ ] **Step 4: Verify it compiles**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw compile -pl herald-core,herald-persistence -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add herald-persistence/pom.xml herald-core/src/main/java/com/herald/config/HeraldConfig.java herald-bot/src/main/resources/application.yaml
git commit -m "feat: add vault vector store and archival config records and dependencies"
```

---

## Task 2: Embedding Model Bean

**Files:**
- Modify: `herald-core/src/main/java/com/herald/agent/ModelProviderConfig.java:1-77`

- [ ] **Step 1: Add embedding model bean to ModelProviderConfig.java**

Add after the `lmstudioChatModel` bean (after line 75), plus the necessary imports:

```java
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
```

```java
@Bean("lmstudioEmbeddingModel")
@ConditionalOnExpression("!T(org.springframework.util.StringUtils).isEmpty('${herald.providers.lmstudio.base-url:}')")
public EmbeddingModel lmstudioEmbeddingModel(HeraldConfig config) {
    var lmstudioConfig = config.providers().lmstudio();
    String apiKey = lmstudioConfig.apiKey() != null ? lmstudioConfig.apiKey() : "lm-studio";
    String baseUrl = lmstudioConfig.baseUrl() != null ? lmstudioConfig.baseUrl() : "http://localhost:1234";
    OpenAiApi api = OpenAiApi.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .build();
    return OpenAiEmbeddingModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiEmbeddingOptions.builder()
                    .model("text-embedding-nomic-embed-text-v2-moe")
                    .build())
            .build();
}
```

**Note:** If `OpenAiEmbeddingModel.builder()` doesn't exist in the SNAPSHOT, fall back to constructor: `new OpenAiEmbeddingModel(api, options)`. Check the actual API at compile time.

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw compile -pl herald-core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add herald-core/src/main/java/com/herald/agent/ModelProviderConfig.java
git commit -m "feat: add LM Studio embedding model bean for Nomic embeddings"
```

---

## Task 3: SimpleVectorStore Bean

**Files:**
- Create: `herald-persistence/src/main/java/com/herald/memory/VaultVectorStoreConfig.java`

- [ ] **Step 1: Create VaultVectorStoreConfig.java**

```java
package com.herald.memory;

import com.herald.config.HeraldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Path;

@Configuration
public class VaultVectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VaultVectorStoreConfig.class);

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel, HeraldConfig config) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        Path storePath = resolveTildePath(config.vaultVectorStorePath());
        File storeFile = storePath.toFile();
        if (storeFile.exists()) {
            log.info("Loading vector store from {}", storePath);
            store.load(storeFile);
        } else {
            log.info("Vector store file not found at {} — starting empty", storePath);
        }

        return store;
    }

    static Path resolveTildePath(String path) {
        if (path.startsWith("~/") || path.equals("~")) {
            return Path.of(System.getProperty("user.home")).resolve(path.substring(2));
        }
        return Path.of(path);
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw compile -pl herald-persistence -q`
Expected: BUILD SUCCESS. If `SimpleVectorStore.builder()` doesn't exist, try `new SimpleVectorStore(embeddingModel)`. If the artifact isn't resolved, the dependency may need `<version>` or a different artifact name — check Maven output.

- [ ] **Step 3: Commit**

```bash
git add herald-persistence/src/main/java/com/herald/memory/VaultVectorStoreConfig.java
git commit -m "feat: add SimpleVectorStore bean with disk persistence"
```

---

## Task 4: Markdown Chunker

**Files:**
- Create: `herald-persistence/src/main/java/com/herald/memory/MarkdownChunker.java`
- Create: `herald-persistence/src/test/java/com/herald/memory/MarkdownChunkerTest.java`

- [ ] **Step 1: Write failing tests for MarkdownChunker**

```java
package com.herald.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker(500, 1000);

    @Test
    void shortFileReturnedWhole() {
        String content = "Short note about weather.";
        List<String> chunks = chunker.chunk(content);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(content);
    }

    @Test
    void longFileChunkedByHeadings() {
        // Build a doc with 3 heading sections, each ~300 chars (total ~900 chars = ~225 tokens > threshold would be if threshold was lower, but we need >500 tokens = >2000 chars)
        StringBuilder sb = new StringBuilder();
        sb.append("# Section One\n\n");
        sb.append("A".repeat(800)).append("\n\n");
        sb.append("## Section Two\n\n");
        sb.append("B".repeat(800)).append("\n\n");
        sb.append("## Section Three\n\n");
        sb.append("C".repeat(800)).append("\n\n");

        List<String> chunks = chunker.chunk(sb.toString());
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0)).contains("Section One");
    }

    @Test
    void emptyContentReturnsEmpty() {
        List<String> chunks = chunker.chunk("");
        assertThat(chunks).isEmpty();
    }

    @Test
    void noHeadingsButLongFileSplitsByParagraph() {
        // >2000 chars, no headings
        String content = ("This is a paragraph.\n\n").repeat(120);
        List<String> chunks = chunker.chunk(content);
        assertThat(chunks).hasSizeGreaterThan(1);
    }

    @Test
    void frontmatterStripped() {
        String content = "---\ntags: [test]\n---\n\n# Title\n\nBody text here.";
        List<String> chunks = chunker.chunk(content);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).doesNotContain("tags: [test]");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw test -pl herald-persistence -Dtest=MarkdownChunkerTest -q`
Expected: FAIL — `MarkdownChunker` class not found

- [ ] **Step 3: Implement MarkdownChunker**

```java
package com.herald.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits markdown content into chunks for vector embedding.
 * Small files (below token threshold) are returned whole.
 * Large files are split on heading boundaries, with fallback to paragraph boundaries.
 */
public class MarkdownChunker {

    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\n.*?\\n---\\n*", Pattern.DOTALL);
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6} ");

    private final int smallFileThreshold; // tokens
    private final int maxChunkSize;       // tokens

    public MarkdownChunker(int smallFileThreshold, int maxChunkSize) {
        this.smallFileThreshold = smallFileThreshold;
        this.maxChunkSize = maxChunkSize;
    }

    public List<String> chunk(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        // Strip YAML frontmatter
        content = FRONTMATTER.matcher(content).replaceFirst("").trim();
        if (content.isEmpty()) {
            return List.of();
        }

        int estimatedTokens = content.length() / 4;
        if (estimatedTokens < smallFileThreshold) {
            return List.of(content);
        }

        // Try splitting by headings
        List<String> sections = splitByHeadings(content);
        if (sections.size() <= 1) {
            // No headings — split by paragraphs
            return splitByParagraphs(content);
        }

        // Merge small sections, split large ones
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String section : sections) {
            int sectionTokens = section.length() / 4;
            int currentTokens = current.length() / 4;

            if (currentTokens + sectionTokens > maxChunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }

            if (sectionTokens > maxChunkSize) {
                // Section itself is too big — split by paragraphs
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                chunks.addAll(splitByParagraphs(section));
            } else {
                current.append(section).append("\n\n");
            }
        }

        if (current.length() > 0) {
            String remaining = current.toString().trim();
            if (!remaining.isEmpty()) {
                chunks.add(remaining);
            }
        }

        return chunks;
    }

    private List<String> splitByHeadings(String content) {
        List<String> sections = new ArrayList<>();
        Matcher matcher = HEADING.matcher(content);
        int lastStart = 0;

        while (matcher.find()) {
            if (matcher.start() > lastStart) {
                String section = content.substring(lastStart, matcher.start()).trim();
                if (!section.isEmpty()) {
                    sections.add(section);
                }
            }
            lastStart = matcher.start();
        }

        if (lastStart < content.length()) {
            String section = content.substring(lastStart).trim();
            if (!section.isEmpty()) {
                sections.add(section);
            }
        }

        return sections;
    }

    private List<String> splitByParagraphs(String content) {
        String[] paragraphs = content.split("\n\n+");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            int paraTokens = para.length() / 4;
            int currentTokens = current.length() / 4;

            if (currentTokens + paraTokens > maxChunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(para).append("\n\n");
        }

        if (current.length() > 0) {
            String remaining = current.toString().trim();
            if (!remaining.isEmpty()) {
                chunks.add(remaining);
            }
        }

        return chunks;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw test -pl herald-persistence -Dtest=MarkdownChunkerTest -q`
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add herald-persistence/src/main/java/com/herald/memory/MarkdownChunker.java herald-persistence/src/test/java/com/herald/memory/MarkdownChunkerTest.java
git commit -m "feat: add adaptive markdown chunker for vault indexing"
```

---

## Task 5: VaultIndexer Service

**Files:**
- Create: `herald-persistence/src/main/java/com/herald/memory/VaultIndexer.java`
- Create: `herald-persistence/src/test/java/com/herald/memory/VaultIndexerTest.java`

- [ ] **Step 1: Write failing tests for VaultIndexer hash tracking**

```java
package com.herald.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VaultIndexerTest {

    @TempDir
    Path tempDir;

    private Path hashesFile;

    @BeforeEach
    void setUp() {
        hashesFile = tempDir.resolve("hashes.json");
    }

    @Test
    void detectsNewFile() throws IOException {
        Path vaultDir = tempDir.resolve("vault");
        Files.createDirectories(vaultDir);
        Files.writeString(vaultDir.resolve("note.md"), "# Hello\n\nWorld");

        Map<String, String> oldHashes = Map.of();
        Map<String, String> newHashes = VaultIndexer.computeFileHashes(vaultDir);

        assertThat(newHashes).hasSize(1);
        assertThat(newHashes).containsKey("note.md");
        assertThat(VaultIndexer.findChanged(oldHashes, newHashes)).containsExactly("note.md");
        assertThat(VaultIndexer.findDeleted(oldHashes, newHashes)).isEmpty();
    }

    @Test
    void detectsModifiedFile() throws IOException {
        Path vaultDir = tempDir.resolve("vault");
        Files.createDirectories(vaultDir);
        Files.writeString(vaultDir.resolve("note.md"), "# Hello\n\nWorld");

        Map<String, String> oldHashes = VaultIndexer.computeFileHashes(vaultDir);

        Files.writeString(vaultDir.resolve("note.md"), "# Hello\n\nUpdated");
        Map<String, String> newHashes = VaultIndexer.computeFileHashes(vaultDir);

        assertThat(VaultIndexer.findChanged(oldHashes, newHashes)).containsExactly("note.md");
    }

    @Test
    void detectsDeletedFile() throws IOException {
        Path vaultDir = tempDir.resolve("vault");
        Files.createDirectories(vaultDir);
        Files.writeString(vaultDir.resolve("note.md"), "content");

        Map<String, String> oldHashes = VaultIndexer.computeFileHashes(vaultDir);

        Files.delete(vaultDir.resolve("note.md"));
        Map<String, String> newHashes = VaultIndexer.computeFileHashes(vaultDir);

        assertThat(VaultIndexer.findDeleted(oldHashes, newHashes)).containsExactly("note.md");
        assertThat(VaultIndexer.findChanged(oldHashes, newHashes)).isEmpty();
    }

    @Test
    void ignoresNonMarkdownFiles() throws IOException {
        Path vaultDir = tempDir.resolve("vault");
        Files.createDirectories(vaultDir);
        Files.writeString(vaultDir.resolve("note.md"), "content");
        Files.writeString(vaultDir.resolve("image.png"), "binary");
        Files.writeString(vaultDir.resolve(".DS_Store"), "meta");

        Map<String, String> hashes = VaultIndexer.computeFileHashes(vaultDir);
        assertThat(hashes).hasSize(1).containsKey("note.md");
    }

    @Test
    void hashPersistenceRoundTrip() throws IOException {
        Map<String, String> hashes = Map.of("a.md", "abc123", "b.md", "def456");
        VaultIndexer.saveHashes(hashesFile, hashes);

        Map<String, String> loaded = VaultIndexer.loadHashes(hashesFile);
        assertThat(loaded).isEqualTo(hashes);
    }

    @Test
    void loadHashesReturnsEmptyWhenFileMissing() {
        Map<String, String> loaded = VaultIndexer.loadHashes(tempDir.resolve("nonexistent.json"));
        assertThat(loaded).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw test -pl herald-persistence -Dtest=VaultIndexerTest -q`
Expected: FAIL — `VaultIndexer` class not found

- [ ] **Step 3: Implement VaultIndexer**

```java
package com.herald.memory;

import com.herald.config.HeraldConfig;
import io.methvin.watcher.DirectoryWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@ConditionalOnBean(SimpleVectorStore.class)
public class VaultIndexer {

    private static final Logger log = LoggerFactory.getLogger(VaultIndexer.class);

    private final SimpleVectorStore vectorStore;
    private final HeraldConfig config;
    private final MarkdownChunker chunker;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vault-watcher-debounce");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> pendingChanges = ConcurrentHashMap.newKeySet();
    private DirectoryWatcher watcher;
    private Map<String, String> currentHashes = new HashMap<>();

    public VaultIndexer(SimpleVectorStore vectorStore, HeraldConfig config) {
        this.vectorStore = vectorStore;
        this.config = config;
        this.chunker = new MarkdownChunker(config.vaultSmallFileThreshold(), config.vaultMaxChunkSize());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onStartup() {
        if (!config.vaultAutoIndexOnStartup()) {
            log.info("Vault auto-indexing disabled");
            return;
        }

        Path vaultPath = resolveVaultPath();
        if (vaultPath == null) return;

        log.info("Starting vault indexing from {}", vaultPath);
        reindex(vaultPath);

        if (config.vaultFileWatcherEnabled()) {
            startFileWatcher(vaultPath);
        }
    }

    /**
     * Full re-index. Called on startup and by vault_reindex tool.
     * Returns summary string.
     */
    public String reindex() {
        Path vaultPath = resolveVaultPath();
        if (vaultPath == null) return "Vault path not configured";
        return reindex(vaultPath);
    }

    private String reindex(Path vaultPath) {
        Path hashesPath = resolveTildePath(config.vaultIndexHashesPath());
        Map<String, String> oldHashes = loadHashes(hashesPath);
        Map<String, String> newHashes = computeFileHashes(vaultPath);

        List<String> changed = findChanged(oldHashes, newHashes);
        List<String> deleted = findDeleted(oldHashes, newHashes);

        int indexed = 0;
        int removed = 0;

        lock.writeLock().lock();
        try {
            // Remove deleted files
            for (String path : deleted) {
                removeDocumentsForFile(path);
                removed++;
            }

            // Index new/changed files
            for (String relativePath : changed) {
                try {
                    Path filePath = vaultPath.resolve(relativePath);
                    String content = Files.readString(filePath);
                    removeDocumentsForFile(relativePath);
                    indexFile(relativePath, content, filePath);
                    indexed++;
                } catch (IOException e) {
                    log.warn("Failed to read {}: {}", relativePath, e.getMessage());
                }
            }

            currentHashes = new HashMap<>(newHashes);
            saveHashes(hashesPath, newHashes);
            persistStore();
        } finally {
            lock.writeLock().unlock();
        }

        String summary = String.format("Vault reindex complete: %d indexed, %d removed, %d total files",
                indexed, removed, newHashes.size());
        log.info(summary);
        return summary;
    }

    private void indexFile(String relativePath, String content, Path filePath) {
        List<String> chunks = chunker.chunk(content);
        String folder = relativePath.contains("/") ? relativePath.substring(0, relativePath.indexOf('/')) : "";
        String filename = filePath.getFileName().toString();
        String lastModified = Instant.ofEpochMilli(filePath.toFile().lastModified()).toString();

        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source_path", relativePath);
            metadata.put("filename", filename);
            metadata.put("folder", folder);
            metadata.put("last_modified", lastModified);
            metadata.put("chunk_index", i);

            documents.add(new Document(chunks.get(i), metadata));
        }

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
            // Track IDs for targeted deletion on future updates
            fileDocumentIds.put(relativePath,
                    documents.stream().map(Document::getId).collect(Collectors.toList()));
        }
    }

    // Tracks document IDs per source file for targeted deletion on update
    private final Map<String, List<String>> fileDocumentIds = new ConcurrentHashMap<>();

    private void removeDocumentsForFile(String relativePath) {
        List<String> docIds = fileDocumentIds.remove(relativePath);
        if (docIds != null && !docIds.isEmpty()) {
            vectorStore.delete(docIds);
        }
    }

    private void persistStore() {
        Path storePath = resolveTildePath(config.vaultVectorStorePath());
        try {
            Path tempFile = storePath.resolveSibling(storePath.getFileName() + ".tmp");
            Files.createDirectories(storePath.getParent());
            vectorStore.save(tempFile.toFile());
            Files.move(tempFile, storePath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("Failed to persist vector store: {}", e.getMessage());
        }
    }

    // --- File Watcher ---

    private void startFileWatcher(Path vaultPath) {
        try {
            watcher = DirectoryWatcher.builder()
                    .path(vaultPath)
                    .listener(event -> {
                        Path changed = event.path();
                        if (changed.toString().endsWith(".md")) {
                            String relative = vaultPath.relativize(changed).toString();
                            pendingChanges.add(relative);
                            scheduleDebouncedReindex(vaultPath);
                        }
                    })
                    .build();
            watcher.watchAsync();
            log.info("File watcher started on {}", vaultPath);
        } catch (IOException e) {
            log.warn("Failed to start file watcher on {}: {}", vaultPath, e.getMessage());
        }
    }

    private void scheduleDebouncedReindex(Path vaultPath) {
        debounceExecutor.schedule(() -> {
            if (pendingChanges.isEmpty()) return;
            Set<String> toProcess = Set.copyOf(pendingChanges);
            pendingChanges.clear();
            log.info("File watcher: processing {} changed files", toProcess.size());
            reindex(vaultPath);
        }, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                log.warn("Error closing file watcher: {}", e.getMessage());
            }
        }
        debounceExecutor.shutdownNow();
    }

    // --- Static utility methods for hashing and diffing ---

    static Map<String, String> computeFileHashes(Path directory) {
        Map<String, String> hashes = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) return hashes;

        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".md"))
                .filter(p -> !p.getFileName().toString().startsWith("."))
                .forEach(p -> {
                    try {
                        String relativePath = directory.relativize(p).toString();
                        String hash = sha256(Files.readString(p));
                        hashes.put(relativePath, hash);
                    } catch (IOException e) {
                        log.warn("Failed to hash {}: {}", p, e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.warn("Failed to walk directory {}: {}", directory, e.getMessage());
        }
        return hashes;
    }

    static List<String> findChanged(Map<String, String> oldHashes, Map<String, String> newHashes) {
        return newHashes.entrySet().stream()
                .filter(e -> !e.getValue().equals(oldHashes.get(e.getKey())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    static List<String> findDeleted(Map<String, String> oldHashes, Map<String, String> newHashes) {
        return oldHashes.keySet().stream()
                .filter(k -> !newHashes.containsKey(k))
                .collect(Collectors.toList());
    }

    static void saveHashes(Path path, Map<String, String> hashes) {
        try {
            Files.createDirectories(path.getParent());
            // Simple JSON serialization without external library
            StringBuilder sb = new StringBuilder("{\n");
            var entries = new ArrayList<>(hashes.entrySet());
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                sb.append("  \"").append(escapeJson(e.getKey())).append("\": \"")
                  .append(escapeJson(e.getValue())).append("\"");
                if (i < entries.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("}");
            Files.writeString(path, sb.toString());
        } catch (IOException e) {
            log.warn("Failed to save hashes to {}: {}", path, e.getMessage());
        }
    }

    static Map<String, String> loadHashes(Path path) {
        if (!Files.exists(path)) return new HashMap<>();
        try {
            String json = Files.readString(path);
            // Simple JSON parsing for flat string->string map
            Map<String, String> map = new LinkedHashMap<>();
            var matcher = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            while (matcher.find()) {
                map.put(matcher.group(1), matcher.group(2));
            }
            return map;
        } catch (IOException e) {
            log.warn("Failed to load hashes from {}: {}", path, e.getMessage());
            return new HashMap<>();
        }
    }

    private Path resolveVaultPath() {
        String vaultPath = config.obsidianVaultPath();
        if (vaultPath.isEmpty()) {
            log.warn("Obsidian vault path not configured (herald.obsidian.vault-path) — skipping vault indexing");
            return null;
        }
        Path path = resolveTildePath(vaultPath);
        if (!Files.isDirectory(path)) {
            log.warn("Obsidian vault path does not exist: {} — skipping vault indexing", path);
            return null;
        }
        return path;
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static Path resolveTildePath(String path) {
        if (path.startsWith("~/") || path.equals("~")) {
            return Path.of(System.getProperty("user.home")).resolve(path.substring(2));
        }
        return Path.of(path);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw test -pl herald-persistence -Dtest=VaultIndexerTest -q`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add herald-persistence/src/main/java/com/herald/memory/VaultIndexer.java herald-persistence/src/test/java/com/herald/memory/VaultIndexerTest.java
git commit -m "feat: add VaultIndexer with startup scan, file watcher, and hash tracking"
```

---

## Task 6: VaultSearchAdvisor

**Files:**
- Create: `herald-persistence/src/main/java/com/herald/agent/VaultSearchAdvisor.java`
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java:284-322`

- [ ] **Step 1: Create VaultSearchAdvisor**

```java
package com.herald.agent;

import com.herald.config.HeraldConfig;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Advisor that auto-injects top-k relevant vault excerpts into the system prompt.
 * Not a @Component — instantiated manually in HeraldAgentConfig like other advisors.
 */
class VaultSearchAdvisor implements CallAdvisor {

    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> false);

    private final VectorStore vectorStore;
    private final int topK;
    private final double minSimilarity;

    VaultSearchAdvisor(VectorStore vectorStore, HeraldConfig config) {
        this.vectorStore = vectorStore;
        this.topK = config.vaultAutoTopK();
        this.minSimilarity = config.vaultMinSimilarity();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (INJECTED.get()) {
            return chain.nextCall(request);
        }
        INJECTED.set(true);
        try {
            String userText = extractUserText(request);
            if (userText == null || userText.isBlank()) {
                return chain.nextCall(request);
            }

            List<Document> results;
            try {
                results = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(userText)
                                .topK(topK)
                                .similarityThreshold(minSimilarity)
                                .build());
            } catch (Exception e) {
                // LM Studio may be down — degrade gracefully
                return chain.nextCall(request);
            }

            if (results == null || results.isEmpty()) {
                return chain.nextCall(request);
            }

            StringBuilder vaultContext = new StringBuilder("\n\n<vault-context>\n");
            vaultContext.append("The following excerpts from your knowledge base may be relevant:\n\n");

            for (Document doc : results) {
                String source = (String) doc.getMetadata().getOrDefault("source_path", "unknown");
                String text = doc.getText();
                if (text.length() > 300) {
                    text = text.substring(0, 300) + "...";
                }
                vaultContext.append("**Source:** ").append(source).append("\n");
                vaultContext.append("> ").append(text.replace("\n", "\n> ")).append("\n\n");
            }

            vaultContext.append("</vault-context>");

            request = request.mutate()
                    .prompt(request.prompt().augmentSystemMessage(
                            existing -> new SystemMessage(existing.getText() + vaultContext)))
                    .build();

            return chain.nextCall(request);
        } finally {
            INJECTED.remove();
        }
    }

    private String extractUserText(ChatClientRequest request) {
        var messages = request.prompt().instructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) {
                return um.getText();
            }
        }
        return null;
    }

    @Override
    public String getName() {
        return "VaultSearchAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 125;
    }
}
```

- [ ] **Step 2: Wire VaultSearchAdvisor into HeraldAgentConfig**

The advisor is manually instantiated in `buildAdvisorChain()`, matching the existing pattern for `MemoryBlockAdvisor` and `ContextCompactionAdvisor`. It is NOT a `@Component`.

In `HeraldAgentConfig.java`, modify the `modelSwitcher` method signature to accept the optional vector store (around line 131, alongside other Optional params):

```java
Optional<SimpleVectorStore> vectorStoreOpt,
```

Add import:
```java
import org.springframework.ai.vectorstore.SimpleVectorStore;
```

Then in `buildAdvisorChain()` method signature (line 284), add the parameter:

```java
List<Advisor> buildAdvisorChain(
        Optional<MemoryTools> memoryToolsOpt,
        Optional<ChatMemory> chatMemoryOpt,
        Optional<SimpleVectorStore> vectorStoreOpt,
        ContextMdAdvisor contextMdAdvisor,
        ChatModel chatModel,
        HeraldConfig config,
        boolean promptDump) {
```

After the `memoryToolsOpt.ifPresent(...)` line (line 299), add:

```java
vectorStoreOpt.ifPresent(vs -> advisors.add(new VaultSearchAdvisor(vs, config)));
```

Update the call site at line 228:

```java
var advisorChain = buildAdvisorChain(memoryToolsOpt, chatMemoryOpt,
        vectorStoreOpt, contextMdAdvisor, chatModel, config, promptDump);
```

- [ ] **Step 3: Verify it compiles**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw compile -pl herald-persistence,herald-bot -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add herald-persistence/src/main/java/com/herald/agent/VaultSearchAdvisor.java herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git commit -m "feat: add VaultSearchAdvisor for auto-injecting vault context"
```

---

## Task 7: VaultSearchTools

**Files:**
- Create: `herald-persistence/src/main/java/com/herald/memory/VaultSearchTools.java`
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java:86-101,324-348`

- [ ] **Step 1: Create VaultSearchTools**

```java
package com.herald.memory;

import com.herald.config.HeraldConfig;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnBean(SimpleVectorStore.class)
public class VaultSearchTools {

    private final SimpleVectorStore vectorStore;
    private final VaultIndexer vaultIndexer;
    private final int defaultTopK;

    public VaultSearchTools(SimpleVectorStore vectorStore, VaultIndexer vaultIndexer, HeraldConfig config) {
        this.vectorStore = vectorStore;
        this.vaultIndexer = vaultIndexer;
        this.defaultTopK = config.vaultToolDefaultTopK();
    }

    @Tool(description = "Search the Obsidian knowledge base using semantic similarity. "
            + "Returns relevant excerpts from archived chat sessions, research notes, "
            + "and other vault content. Use this for deeper searches beyond the auto-injected vault context.")
    public String vault_search(
            @ToolParam(description = "Natural language search query") String query,
            @ToolParam(description = "Number of results to return (default 5)", required = false) Integer topK) {

        int k = topK != null && topK > 0 ? topK : defaultTopK;

        List<Document> results;
        try {
            results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(k)
                            .similarityThreshold(0.5) // lower threshold for explicit searches
                            .build());
        } catch (Exception e) {
            return "Vault search failed (embedding model may be unavailable): " + e.getMessage();
        }

        if (results == null || results.isEmpty()) {
            return "No results found for query: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" result(s):\n\n");

        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            String source = (String) doc.getMetadata().getOrDefault("source_path", "unknown");
            String folder = (String) doc.getMetadata().getOrDefault("folder", "");
            String text = doc.getText();
            if (text.length() > 200) {
                text = text.substring(0, 200) + "...";
            }

            sb.append(i + 1).append(". **").append(source).append("**");
            if (!folder.isEmpty()) {
                sb.append(" (").append(folder).append(")");
            }
            sb.append("\n   ").append(text.replace("\n", "\n   ")).append("\n\n");
        }

        return sb.toString();
    }

    @Tool(description = "Re-scan and re-index the Obsidian vault. Use if you suspect the search index is stale "
            + "or if files have been added/modified outside of Herald.")
    public String vault_reindex() {
        return vaultIndexer.reindex();
    }
}
```

- [ ] **Step 2: Wire VaultSearchTools into HeraldAgentConfig**

In `HeraldAgentConfig.java`:

Add to `modelSwitcher` method parameters (alongside other Optional params):
```java
Optional<VaultSearchTools> vaultSearchToolsOpt,
```

In `activeToolNames` method (line 88), add parameter and registration:
```java
Optional<VaultSearchTools> vaultSearchTools
```
And after `gwsTools.ifPresent(...)` (line 99):
```java
vaultSearchTools.ifPresent(t -> names.addAll(List.of("vault_search", "vault_reindex")));
```

In `buildToolList` method (line 324), add parameter:
```java
Optional<VaultSearchTools> vaultSearchToolsOpt
```
And after `cronToolsOpt.ifPresent(tools::add)` (line 345):
```java
vaultSearchToolsOpt.ifPresent(tools::add);
```

Update the call sites for both `activeToolNames` and `buildToolList` accordingly.

- [ ] **Step 3: Verify it compiles**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw compile -pl herald-persistence,herald-bot -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add herald-persistence/src/main/java/com/herald/memory/VaultSearchTools.java herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git commit -m "feat: add vault_search and vault_reindex tools"
```

---

## Task 8: ChatArchivalJob Changes

**Files:**
- Modify: `herald-persistence/src/main/java/com/herald/memory/ChatArchivalJob.java:1-258`
- Create: `herald-persistence/src/test/java/com/herald/memory/ChatArchivalJobTest.java`

- [ ] **Step 1: Write failing tests for new archival triggers**

```java
package com.herald.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ChatArchivalJob archival trigger logic.
 * Does NOT test Obsidian CLI integration — only the decision logic.
 */
class ChatArchivalJobTest {

    // These test the static/package-private decision methods.
    // We extract the trigger logic into testable methods.

    @Test
    void countTriggerFiresAboveThreshold() {
        assertThat(ChatArchivalJob.shouldArchive(6, false, 5, 2)).isTrue();
    }

    @Test
    void countTriggerDoesNotFireAtOrBelowThreshold() {
        assertThat(ChatArchivalJob.shouldArchive(5, false, 5, 2)).isFalse();
        assertThat(ChatArchivalJob.shouldArchive(3, false, 5, 2)).isFalse();
    }

    @Test
    void idleTriggerFiresWithEnoughMessages() {
        assertThat(ChatArchivalJob.shouldArchive(2, true, 5, 2)).isTrue();
        assertThat(ChatArchivalJob.shouldArchive(3, true, 5, 2)).isTrue();
    }

    @Test
    void idleTriggerDoesNotFireWithOneMessage() {
        assertThat(ChatArchivalJob.shouldArchive(1, true, 5, 2)).isFalse();
    }

    @Test
    void noTriggerWhenNotIdleAndBelowThreshold() {
        assertThat(ChatArchivalJob.shouldArchive(3, false, 5, 2)).isFalse();
    }

    @Test
    void isIdleReturnsTrueForOldTimestamp() {
        String old = LocalDateTime.now().minusMinutes(45).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertThat(ChatArchivalJob.isTimestampIdle(old, 30)).isTrue();
    }

    @Test
    void isIdleReturnsFalseForRecentTimestamp() {
        String recent = LocalDateTime.now().minusMinutes(5).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertThat(ChatArchivalJob.isTimestampIdle(recent, 30)).isFalse();
    }

    @Test
    void isIdleReturnsFalseForNull() {
        assertThat(ChatArchivalJob.isTimestampIdle(null, 30)).isFalse();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw test -pl herald-persistence -Dtest=ChatArchivalJobTest -q`
Expected: FAIL — methods `shouldArchive` and `isTimestampIdle` not found

- [ ] **Step 3: Modify ChatArchivalJob**

Key changes to `ChatArchivalJob.java`:

1. Remove the hardcoded constants (lines 33-34) — they are now in config.

2. Add `HeraldConfig` dependency to constructor:
```java
private final HeraldConfig config;

public ChatArchivalJob(JdbcTemplate jdbcTemplate, HeraldConfig config) {
    this.jdbcTemplate = jdbcTemplate;
    this.config = config;
}
```

3. Change schedule to 15 minutes (line 45):
```java
@Scheduled(fixedRate = 900_000, initialDelay = 180_000)
```

4. Replace the loop in `archiveSessions()` (lines 62-77):
```java
for (String conversationId : conversationIds) {
    int count = countMessages(conversationId);
    int threshold = config.archivalMessageThreshold();
    boolean idle = isConversationIdle(conversationId);

    if (!shouldArchive(count, idle, threshold, config.archivalMinMessagesForIdle())) {
        continue;
    }

    String summary = buildSessionSummary(conversationId);
    if (summary.isBlank()) {
        continue;
    }

    if (archiveToObsidian(conversationId, summary)) {
        trimOldMessages(conversationId);
        archived++;
    }
}
```

5. Add the new methods:
```java
private boolean isConversationIdle(String conversationId) {
    String lastTimestamp = jdbcTemplate.queryForObject(
        "SELECT MAX(timestamp) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?",
        String.class, conversationId);
    return isTimestampIdle(lastTimestamp, config.archivalIdleTimeoutMinutes());
}

// Package-private for testing
static boolean shouldArchive(int messageCount, boolean isIdle, int messageThreshold, int minMessagesForIdle) {
    if (messageCount > messageThreshold) return true;
    if (isIdle && messageCount >= minMessagesForIdle) return true;
    return false;
}

static boolean isTimestampIdle(String timestamp, long idleMinutes) {
    if (timestamp == null) return false;
    try {
        LocalDateTime lastMsg = LocalDateTime.parse(timestamp);
        return lastMsg.plusMinutes(idleMinutes).isBefore(LocalDateTime.now());
    } catch (Exception e) {
        return false;
    }
}
```

6. Update `buildSessionSummary` to use `config.archivalKeepRecentMessages()` instead of the constant:
```java
int archiveEnd = Math.max(0, messages.size() - config.archivalKeepRecentMessages());
```

7. Update `trimOldMessages` similarly:
```java
int keepRecent = config.archivalKeepRecentMessages();
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw test -pl herald-persistence -Dtest=ChatArchivalJobTest -q`
Expected: All 8 tests PASS

- [ ] **Step 5: Run full module tests**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw test -pl herald-persistence -q`
Expected: All tests PASS (including MarkdownChunker and VaultIndexer tests)

- [ ] **Step 6: Commit**

```bash
git add herald-persistence/src/main/java/com/herald/memory/ChatArchivalJob.java herald-persistence/src/test/java/com/herald/memory/ChatArchivalJobTest.java
git commit -m "feat: lower archival thresholds and add idle detection to ChatArchivalJob"
```

---

## Task 9: System Prompt Update

**Files:**
- Modify: `herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md:42-77`

- [ ] **Step 1: Add vault search documentation to system prompt**

After the "Cold Memory (Obsidian)" section (after line 51), add:

```markdown
## Semantic Vault Search (Vector Store)
When available, Herald automatically searches the Obsidian vault for relevant context on each turn.
- The `<vault-context>` block in the system prompt contains auto-retrieved excerpts — use them naturally without mentioning the block itself.
- Use `vault_search(query)` for deeper semantic searches when the auto-injected context is insufficient or when the user asks about past conversations, research, or archived knowledge.
- Use `vault_reindex()` to force a re-scan if you suspect the search index is stale or if files were recently added.
- Vault search complements (does not replace) the Obsidian CLI tools — use CLI for creating notes, vault_search for finding relevant content.
```

- [ ] **Step 2: Commit**

```bash
git add herald-core/src/main/resources/prompts/MAIN_AGENT_SYSTEM_PROMPT.md
git commit -m "docs: add vault search instructions to system prompt"
```

---

## Task 10: End-to-End Verification

**Files:** None created — verification only.

- [ ] **Step 1: Compile the entire project**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run all tests**

Run: `cd /Users/dbbaskette/Projects/herald && ./mvnw test -q`
Expected: All tests PASS

- [ ] **Step 3: Manual smoke test**

Ensure LM Studio is running on localhost:1234 with the Nomic embedding model loaded, and `LMSTUDIO_BASE_URL=http://localhost:1234` is set in `.env`.

Start Herald:
```bash
cd /Users/dbbaskette/Projects/herald && ./mvnw spring-boot:run -pl herald-bot
```

Watch logs for:
- `"Loading vector store from..."` or `"Vector store file not found..."`
- `"Starting vault indexing from..."` followed by `"Vault reindex complete: N indexed..."`
- `"File watcher started on..."`

Send a test message via Telegram and check logs for `VaultSearchAdvisor` running similarity search.

- [ ] **Step 4: Verify vault_search tool works**

Ask Herald: "search the vault for weather"
Expected: Herald calls `vault_search` and returns results (or "No results" if vault is empty).

- [ ] **Step 5: Verify archival triggers on short conversation**

Check that after 30 minutes of idle, a conversation with 2+ messages gets archived to `Chat-Sessions/` in Obsidian.

- [ ] **Step 6: Final commit if any fixups needed**

```bash
git add -A
git commit -m "fix: address issues found during smoke testing"
```
