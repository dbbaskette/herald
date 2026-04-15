# Herald Vault Vector Store — Design Spec

**Date:** 2026-03-23
**Status:** Approved
**Goal:** Add semantic search over the Obsidian cold memory vault using local embeddings and Spring AI's SimpleVectorStore.

---

## Context

Herald has a two-tier memory system: hot memory (SQLite key-value) and cold memory (Obsidian vault). Cold memory is currently only accessed when the LLM decides to search via CLI tools — there is no automatic retrieval. This means relevant context is frequently missed.

This design adds a vector store that:
1. Automatically indexes Obsidian vault markdown files with local Nomic embeddings
2. Injects top-k relevant context into every turn (lightweight advisor)
3. Provides a deeper search tool the agent can invoke explicitly
4. Watches for file changes and re-indexes incrementally

## Constraints

- Must run locally, no cloud dependencies
- Optional — Herald must start and function without LM Studio running
- Pure Java (no Chroma/Python server process)
- Vault path read from existing config: `herald.obsidian.vault-path` (resolves to iCloud Obsidian directory)

---

## Architecture

### Embedding Model

Defined in `ModelProviderConfig.java` alongside the existing `lmstudioChatModel` bean:

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

**Note:** Verify that `OpenAiEmbeddingModel` and `OpenAiEmbeddingOptions` exist in Spring AI 2.0.0-SNAPSHOT at build time. Class names have shifted between milestones. If the builder pattern differs, adapt to match the actual API.

### Vector Store

- Spring AI `SimpleVectorStore` — pure Java, serializes to JSON on disk
- Persistence path: `~/.herald/vector-store.json`
- Conditional on `EmbeddingModel` bean availability
- Loaded from disk on startup if file exists
- Defined in `herald-persistence` module (new `VaultVectorStoreConfig` class)
- Persistence strategy: write to temp file then atomic rename to prevent corruption on crash

**Known limitation:** `SimpleVectorStore` does not support `FilterExpression`-based metadata filtering. All filtering (e.g., by folder) is done as post-filtering in application code after similarity search. This means `vault_search` with a folder filter fetches extra results and filters in Java.

**Dependency:** `SimpleVectorStore` is in `spring-ai-core`, which is already transitively included via the Anthropic starter. Verify at build time — if not present, add `spring-ai-vector-store-simple` to `herald-persistence/pom.xml`.

### VaultIndexer Service

Lives in `herald-persistence`, package `com.herald.memory`.

**Startup indexing:**
- Scans the Obsidian vault directory (from `herald.obsidian.vault-path` config) recursively for `.md` files
- For each file, computes a content hash (SHA-256 of file content)
- Compares against a sidecar index (`~/.herald/vault-index-hashes.json`) mapping `path -> hash`
- New/changed files: chunk and index (see Chunking below)
- Deleted files: remove vectors with matching `source_path` metadata
- Updated files: remove old vectors, re-index
- Runs asynchronously after startup (does not block boot)
- Conditional on `herald.vault.auto-index-on-startup` (default true)

**File watcher:**
- Uses `io.methvin:directory-watcher` library for native macOS FSEvents support (Java's built-in `WatchService` uses slow polling on macOS and does not handle recursive directories)
- Events: create, modify, delete on `.md` files
- Debounce: 2-second window to coalesce rapid events (iCloud sync can fire multiples)
- Overflow/error handling: trigger full re-scan
- Runs on a daemon thread, conditional on `herald.vault.file-watcher-enabled`
- Persistence: after processing a batch of changes, persist vector store to disk (write to temp file, atomic rename)

**Manual reindex:**
- Exposed as `@Tool` method: `vault_reindex()` — forces a full re-scan

### Adaptive Chunking

- **Small files** (< 500 tokens): indexed as a single document
- **Large files** (>= 500 tokens): split on markdown heading boundaries (`# `, `## `, `### `, etc.)
  - Each chunk targets ~500-1000 tokens
  - If a single heading section exceeds max chunk size, split further at paragraph boundaries
  - No overlap between chunks for initial implementation (add later if retrieval quality is poor)
- **Token estimation:** character count / 4 (rough approximation, sufficient for chunking decisions)

**Metadata per chunk/document:**
- `source_path` — relative path within vault (e.g., `Chat-Sessions/2026-03-10-weather.md`)
- `filename` — just the filename
- `folder` — top-level folder (e.g., `Chat-Sessions`, `Research`, `Daily`)
- `last_modified` — file last-modified timestamp (ISO-8601)
- `chunk_index` — 0 for whole-file docs, 0..N for chunked docs
- `content_hash` — SHA-256 of the chunk content (for deduplication)

### VaultSearchAdvisor

New advisor in the chain, inserted between `MemoryBlockAdvisor` and `ContextCompactionAdvisor`.

**Ordering:** `Ordered.HIGHEST_PRECEDENCE + 125`

**Wiring in `HeraldAgentConfig.buildAdvisorChain()`:**
```java
// Inject as Optional, same pattern as MemoryBlockAdvisor
@Autowired(required = false) VaultSearchAdvisor vaultSearchAdvisor;

// In buildAdvisorChain():
if (vaultSearchAdvisor != null) {
    advisors.add(vaultSearchAdvisor);
}
```

**Behavior:**
- On each user message, extracts the user's text
- Runs similarity search against the vector store: top-2 results, minimum similarity 0.7
- If results found, injects a `<vault-context>` block into the system prompt:
  ```
  <vault-context>
  The following excerpts from your knowledge base may be relevant:

  **Source:** Chat-Sessions/2026-03-10-weather.md
  > [chunk text, truncated to ~300 chars for prompt efficiency]

  **Source:** Research/spring-ai-patterns.md
  > [chunk text]
  </vault-context>
  ```
- If no results above threshold, injects nothing (zero token cost)
- Uses `ThreadLocal` flag to skip re-injection on tool-call advisor iterations (same pattern as `MemoryBlockAdvisor`)
- Conditional — `@ConditionalOnBean(VectorStore.class)`, skipped entirely if vector store unavailable

**Known trade-off:** Vault context tokens injected by this advisor are not accounted for by `ContextCompactionAdvisor`'s token budget calculation. With top-2 results at ~300 chars each, this is ~150 tokens — negligible. If auto-top-k is increased in the future, this should be revisited.

### vault_search Tool

New tool class `VaultSearchTools` in `herald-persistence`, package `com.herald.memory`.

**Methods:**
- `vault_search(query, top_k)` — semantic search
  - `query` (required): natural language search text
  - `top_k` (optional, default 5): number of results
  - Returns: ranked list with source path, similarity score, and text preview (~200 chars)
  - Post-filters in Java if caller wants folder restriction (future enhancement — not exposed as parameter in v1 since `SimpleVectorStore` can't filter natively)

- `vault_reindex()` — triggers full re-scan and re-index of the vault
  - Returns: summary of files indexed/updated/removed

**Registration in `HeraldAgentConfig.java`:**
```java
// Inject as Optional, same pattern as MemoryTools
@Autowired private Optional<VaultSearchTools> vaultSearchToolsOpt;

// In buildToolList():
vaultSearchToolsOpt.ifPresent(tools::add);

// In activeToolNames:
vaultSearchToolsOpt.ifPresent(t -> toolNames.addAll(
    List.of("vault_search", "vault_reindex")));
```

### System Prompt Update

Add a section to `MAIN_AGENT_SYSTEM_PROMPT.md`:
- The `<vault-context>` block contains auto-retrieved relevant excerpts from cold memory
- `vault_search` is available for deeper semantic queries when auto-injected context is insufficient
- `vault_reindex` forces a re-scan if the agent suspects the index is stale

---

## Configuration

Added to `application.yaml` under `herald:`:

```yaml
herald:
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
      small-file-threshold: 500   # tokens (estimated as chars/4)
      max-chunk-size: 1000        # tokens
```

Added to `HeraldConfig.java` (with null-safe convenience methods following existing pattern):

```java
record Vault(
    String vectorStorePath,
    String indexHashesPath,
    Boolean autoIndexOnStartup,
    Boolean fileWatcherEnabled,
    VaultSearch search,
    VaultChunking chunking
) {
    record VaultSearch(Integer autoTopK, Integer toolDefaultTopK, Double minSimilarity) {}
    record VaultChunking(Integer smallFileThreshold, Integer maxChunkSize) {}
}

// Convenience methods on HeraldConfig:
public String vaultVectorStorePath() {
    return vault() != null && vault().vectorStorePath() != null
        ? vault().vectorStorePath() : "~/.herald/vector-store.json";
}
public boolean vaultAutoIndexOnStartup() {
    return vault() != null && vault().autoIndexOnStartup() != null
        ? vault().autoIndexOnStartup() : true;
}
public boolean vaultFileWatcherEnabled() {
    return vault() != null && vault().fileWatcherEnabled() != null
        ? vault().fileWatcherEnabled() : true;
}
public int vaultAutoTopK() {
    return vault() != null && vault().search() != null && vault().search().autoTopK() != null
        ? vault().search().autoTopK() : 2;
}
public double vaultMinSimilarity() {
    return vault() != null && vault().search() != null && vault().search().minSimilarity() != null
        ? vault().search().minSimilarity() : 0.7;
}
// ... etc for remaining fields
```

---

## Dependencies to Add

**herald-persistence `pom.xml`:**
```xml
<!-- Verify at build time: SimpleVectorStore may already be in spring-ai-core (transitive).
     If not, add this: -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-vector-store-simple</artifactId>
</dependency>

<!-- Native file watching on macOS (FSEvents) -->
<dependency>
    <groupId>io.methvin</groupId>
    <artifactId>directory-watcher</artifactId>
    <version>0.18.0</version>
</dependency>
```

---

## Module Placement

| Component | Module | Package |
|---|---|---|
| `EmbeddingModel` bean | herald-core | `com.herald.agent` (in `ModelProviderConfig`) |
| `VaultVectorStoreConfig` | herald-persistence | `com.herald.memory` |
| `VaultIndexer` service | herald-persistence | `com.herald.memory` |
| `VaultSearchAdvisor` | herald-persistence | `com.herald.agent` |
| `VaultSearchTools` | herald-persistence | `com.herald.memory` |
| Config records | herald-core | `com.herald.config` (extend `HeraldConfig`) |

---

## Data Flow

```
STARTUP:
  Herald boots
    -> EmbeddingModel bean created (if LM Studio URL configured)
    -> SimpleVectorStore bean created (if EmbeddingModel available)
    -> VaultIndexer.indexOnStartup() runs async
      -> Scan vault .md files (path from herald.obsidian.vault-path)
      -> Compare hashes against sidecar index
      -> Chunk new/changed files (adaptive: whole-file or heading-split)
      -> Embed via LM Studio /v1/embeddings
      -> Store in SimpleVectorStore
      -> Persist to ~/.herald/vector-store.json (atomic write)
    -> File watcher thread starts (if enabled, using directory-watcher)

EACH CONVERSATION TURN:
  User message arrives
    -> DateTimePromptAdvisor (+50, injects time)
    -> ContextMdAdvisor (+75, injects CONTEXT.md)
    -> MemoryBlockAdvisor (+100, injects hot memory)
    -> VaultSearchAdvisor (+125, NEW — top-2 similarity search, injects vault context)
    -> ContextCompactionAdvisor (+150, manages token budget)
    -> OneShotMemoryAdvisor (LOWEST-100, loads/saves chat history)
    -> ToolCallAdvisor (LOWEST-1, agent may call vault_search for deeper queries)

FILE CHANGE (watcher):
  FS event -> debounce (2s) -> re-chunk affected file -> update vectors -> atomic persist

Note: Advisors are sorted by getOrder(), not insertion order in buildAdvisorChain().
```

---

## ChatArchivalJob Changes

The existing `ChatArchivalJob` only archives conversations with >20 messages, which means most short conversations are never persisted to cold memory. This change lowers the threshold and adds idle-based archival so that meaningful short conversations are captured.

### Current Behavior
- Runs hourly (`fixedRate = 3600_000`, 3-min initial delay)
- Only archives conversations with >20 messages
- Keeps 20 most recent, deletes the rest after writing to Obsidian

### New Behavior

**Two archival triggers (OR logic):**

1. **Message count threshold (lowered):** Archive if conversation has >5 messages (down from 20). Keeps the 5 most recent in SQLite after archiving.

2. **Idle timeout:** Archive if conversation has >= 2 messages AND no new message in the last 30 minutes. This catches short but complete conversations. After archiving, keep all messages (they're recent enough to be useful) — or optionally trim to the last 5.

**Implementation changes to `ChatArchivalJob.java`:**

```java
private static final int KEEP_RECENT_MESSAGES = 5;         // was 20
private static final int MIN_MESSAGES_FOR_COUNT_TRIGGER = 5; // was 20
private static final int MIN_MESSAGES_FOR_IDLE_TRIGGER = 2;  // new
private static final long IDLE_TIMEOUT_MINUTES = 30;         // new
```

**In `archiveSessions()`:**
```java
for (String conversationId : conversationIds) {
    int count = countMessages(conversationId);
    boolean countTrigger = count > MIN_MESSAGES_FOR_COUNT_TRIGGER;
    boolean idleTrigger = count >= MIN_MESSAGES_FOR_IDLE_TRIGGER
                          && isConversationIdle(conversationId, IDLE_TIMEOUT_MINUTES);

    if (!countTrigger && !idleTrigger) {
        continue;
    }
    // ... existing archive + trim logic
}
```

**New method `isConversationIdle()`:**
```java
private boolean isConversationIdle(String conversationId, long idleMinutes) {
    String lastTimestamp = jdbcTemplate.queryForObject(
        "SELECT MAX(timestamp) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?",
        String.class, conversationId);
    if (lastTimestamp == null) return false;
    LocalDateTime lastMsg = LocalDateTime.parse(lastTimestamp);
    return lastMsg.plusMinutes(idleMinutes).isBefore(LocalDateTime.now());
}
```

**Schedule change:** Consider running more frequently (every 15 minutes instead of hourly) so idle conversations are caught promptly:
```java
@Scheduled(fixedRate = 900_000, initialDelay = 180_000)  // 15 min
```

### Configuration

Add to `application.yaml`:
```yaml
herald:
  archival:
    message-threshold: 5
    idle-timeout-minutes: 30
    min-messages-for-idle: 2
    run-interval-ms: 900000    # 15 minutes
    keep-recent-messages: 5
```

Add to `HeraldConfig.java`:
```java
record Archival(
    Integer messageThreshold,
    Integer idleTimeoutMinutes,
    Integer minMessagesForIdle,
    Long runIntervalMs,
    Integer keepRecentMessages
) {}
```

### Integration with Vector Store

No extra wiring needed. The archival job writes `.md` files to the Obsidian vault's `Chat-Sessions/` folder. The `VaultIndexer` file watcher detects the new file and indexes it automatically:

```
Conversation idle 30min
  -> ChatArchivalJob writes Chat-Sessions/2026-03-23-1430-conv123.md
  -> File watcher detects new .md
  -> VaultIndexer chunks + embeds
  -> Searchable via vault_search and VaultSearchAdvisor
```

---

## What Does NOT Change

- Hot memory (SQLite key-value store) — unchanged
- Obsidian CLI tools (`memory_list_cold`, etc.) — unchanged, still available
- Existing advisor chain — all existing advisors unchanged, new one inserted
- Memory migration job — unchanged
- Herald starts and works without LM Studio — all vector components are conditional

---

## Known Limitations

1. **No native metadata filtering** — `SimpleVectorStore` ignores `FilterExpression`. Folder-based filtering must be done as post-filtering in application code. If this becomes a bottleneck, swap to a vector store that supports filtering.
2. **Vault context not in token budget** — `ContextCompactionAdvisor` does not account for the ~150 tokens injected by `VaultSearchAdvisor`. Negligible at top-2, revisit if auto-top-k grows.
3. **Thread safety** — `SimpleVectorStore.add()` (watcher thread) and `similaritySearch()` (request thread) may run concurrently. `SimpleVectorStore` uses a `ConcurrentHashMap` internally, but `save()` serializes the full store. Use a `ReentrantReadWriteLock` in `VaultIndexer` to guard add+save vs. search.

---

## Testing Strategy

- **Unit tests** (no LM Studio needed):
  - `VaultIndexer` chunking logic with sample markdown files (small, large, heading-heavy)
  - Hash-based change detection (new/modified/deleted scenarios)
  - Post-filter logic for folder restriction
  - Mock `EmbeddingModel` and `VectorStore` for indexer tests
- **Integration tests** (requires LM Studio running):
  - Embedding + store round-trip: index a file, search for it, verify result
  - Advisor injection: verify `<vault-context>` appears in system prompt
- **Conditional bean tests:**
  - Advisor skips cleanly when vector store is absent
  - Herald starts without LM Studio — no vector beans created, no errors
- **File watcher tests:**
  - Debounce behavior (multiple rapid events coalesced)
  - Graceful degradation: LM Studio stops mid-session, watcher continues but queues indexing
- **ChatArchivalJob tests:**
  - Conversation with >5 messages triggers archival
  - Conversation with 2-5 messages triggers after 30min idle
  - Conversation with 1 message is never archived
  - Archived files appear in Obsidian vault and get picked up by file watcher
