package com.herald.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.herald.config.HeraldConfig;
import io.methvin.watcher.DirectoryChangeEvent;
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
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class VaultIndexer {

    private static final Logger log = LoggerFactory.getLogger(VaultIndexer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SimpleVectorStore vectorStore;
    private final HeraldConfig config;
    private final MarkdownChunker chunker;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, List<String>> fileDocumentIds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, ScheduledFuture<?>> pendingEvents = new ConcurrentHashMap<>();

    private DirectoryWatcher watcher;

    public VaultIndexer(SimpleVectorStore vectorStore, HeraldConfig config) {
        this.vectorStore = vectorStore;
        this.config = config;
        this.chunker = new MarkdownChunker(config.vaultSmallFileThreshold(), config.vaultMaxChunkSize());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("VaultIndexer startup triggered");
        if (config.vaultAutoIndexOnStartup()) {
            try {
                reindex();
            } catch (Exception e) {
                log.error("Failed to run startup vault indexing", e);
            }
        }
        if (config.vaultFileWatcherEnabled()) {
            startFileWatcher();
        }
    }

    /**
     * Full reindex: scans vault, detects changes via hash comparison, indexes changed files,
     * removes deleted file documents, and persists updated hashes.
     */
    public String reindex() {
        String vaultPathStr = config.obsidianVaultPath();
        if (vaultPathStr == null || vaultPathStr.isBlank()) {
            log.warn("Obsidian vault path not configured — skipping reindex");
            return "Vault path not configured";
        }

        Path vaultPath = resolveTildePath(vaultPathStr);
        if (!Files.isDirectory(vaultPath)) {
            log.warn("Vault path does not exist or is not a directory: {}", vaultPath);
            return "Vault path does not exist: " + vaultPath;
        }

        Path hashesPath = resolveTildePath(config.vaultIndexHashesPath());
        log.info("Starting vault reindex from {}", vaultPath);

        lock.writeLock().lock();
        try {
            Map<String, String> oldHashes = loadHashes(hashesPath);
            Map<String, String> newHashes = computeFileHashes(vaultPath);

            List<String> changed = findChanged(oldHashes, newHashes);
            List<String> deleted = findDeleted(oldHashes, newHashes);

            log.info("Vault scan: {} files total, {} changed/new, {} deleted",
                    newHashes.size(), changed.size(), deleted.size());

            for (String relativePath : deleted) {
                removeDocumentsForFile(relativePath);
            }

            for (String relativePath : changed) {
                indexFile(vaultPath, relativePath);
            }

            saveHashes(hashesPath, newHashes);
            saveVectorStore();

            String summary = String.format("Vault reindex complete: %d indexed, %d removed, %d total files",
                    changed.size(), deleted.size(), newHashes.size());
            log.info(summary);
            return summary;
        } catch (Exception e) {
            log.error("Error during vault reindex", e);
            return "Reindex failed: " + e.getMessage();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Index a single file: chunk it, create documents with metadata, add to vector store,
     * and track document IDs for later targeted deletion.
     */
    private void indexFile(Path vaultPath, String relativePath) {
        try {
            Path filePath = vaultPath.resolve(relativePath);
            if (!Files.exists(filePath)) {
                return;
            }

            // Remove old documents for this file first
            removeDocumentsForFile(relativePath);

            String content = Files.readString(filePath);
            List<String> chunks = chunker.chunk(content);
            if (chunks.isEmpty()) {
                return;
            }

            String filename = filePath.getFileName().toString();
            String folder = relativePath.contains("/")
                    ? relativePath.substring(0, relativePath.lastIndexOf('/'))
                    : "";
            String lastModified = Instant.ofEpochMilli(Files.getLastModifiedTime(filePath).toMillis()).toString();

            List<Document> documents = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source_path", relativePath);
                metadata.put("filename", filename);
                metadata.put("folder", folder);
                metadata.put("last_modified", lastModified);
                metadata.put("chunk_index", i);

                documents.add(new Document(chunks.get(i), metadata));
            }

            vectorStore.add(documents);
            fileDocumentIds.put(relativePath,
                    documents.stream().map(Document::getId).collect(Collectors.toList()));

            log.debug("Indexed {} ({} chunks)", relativePath, chunks.size());
        } catch (IOException e) {
            log.error("Failed to index file: {}", relativePath, e);
        }
    }

    /**
     * Remove all tracked documents for a given file from the vector store.
     */
    private void removeDocumentsForFile(String relativePath) {
        List<String> docIds = fileDocumentIds.remove(relativePath);
        if (docIds != null && !docIds.isEmpty()) {
            vectorStore.delete(docIds);
            log.debug("Removed {} documents for {}", docIds.size(), relativePath);
        }
    }

    private void saveVectorStore() {
        try {
            Path storePath = resolveTildePath(config.vaultVectorStorePath());
            Files.createDirectories(storePath.getParent());
            vectorStore.save(storePath.toFile());
        } catch (IOException e) {
            log.error("Failed to save vector store", e);
        }
    }

    // --- File Watcher ---

    private void startFileWatcher() {
        String vaultPathStr = config.obsidianVaultPath();
        if (vaultPathStr == null || vaultPathStr.isBlank()) {
            return;
        }

        Path vaultPath = resolveTildePath(vaultPathStr);
        if (!Files.isDirectory(vaultPath)) {
            return;
        }

        try {
            watcher = DirectoryWatcher.builder()
                    .path(vaultPath)
                    .listener(event -> handleFileEvent(vaultPath, event))
                    .build();
            watcher.watchAsync();
            log.info("File watcher started for {}", vaultPath);
        } catch (IOException e) {
            log.error("Failed to start file watcher", e);
        }
    }

    private void handleFileEvent(Path vaultPath, DirectoryChangeEvent event) {
        Path eventPath = event.path();
        if (eventPath == null || !eventPath.toString().endsWith(".md")) {
            return;
        }

        String relativePath = vaultPath.relativize(eventPath).toString();

        // Debounce: cancel any pending event for this file, schedule new one in 2 seconds
        ScheduledFuture<?> existing = pendingEvents.remove(relativePath);
        if (existing != null) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = debounceExecutor.schedule(() -> {
            pendingEvents.remove(relativePath);
            lock.writeLock().lock();
            try {
                Path hashesPath = resolveTildePath(config.vaultIndexHashesPath());

                switch (event.eventType()) {
                    case CREATE, MODIFY -> {
                        indexFile(vaultPath, relativePath);
                        Map<String, String> hashes = loadHashes(hashesPath);
                        Map<String, String> updated = new HashMap<>(hashes);
                        updated.put(relativePath, hashFile(eventPath));
                        saveHashes(hashesPath, updated);
                        saveVectorStore();
                        log.info("File watcher: indexed {}", relativePath);
                    }
                    case DELETE -> {
                        removeDocumentsForFile(relativePath);
                        Map<String, String> hashes = loadHashes(hashesPath);
                        Map<String, String> updated = new HashMap<>(hashes);
                        updated.remove(relativePath);
                        saveHashes(hashesPath, updated);
                        saveVectorStore();
                        log.info("File watcher: removed {}", relativePath);
                    }
                    default -> { }
                }
            } catch (Exception e) {
                log.error("Error handling file event for {}", relativePath, e);
            } finally {
                lock.writeLock().unlock();
            }
        }, 2, TimeUnit.SECONDS);

        pendingEvents.put(relativePath, future);
    }

    @PreDestroy
    public void shutdown() {
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                log.warn("Error closing file watcher", e);
            }
        }
        debounceExecutor.shutdownNow();
    }

    // --- Read lock for search operations ---

    public ReentrantReadWriteLock.ReadLock readLock() {
        return lock.readLock();
    }

    // --- Static Utility Methods ---

    /**
     * Compute SHA-256 hashes for all .md files in the given directory (recursively).
     */
    public static Map<String, String> computeFileHashes(Path directory) throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            return hashes;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".md")) {
                    String relativePath = directory.relativize(file).toString();
                    try {
                        hashes.put(relativePath, hashFile(file));
                    } catch (IOException e) {
                        log.warn("Failed to hash file: {}", relativePath, e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return hashes;
    }

    /**
     * Find files that are new or have changed hash values.
     */
    public static List<String> findChanged(Map<String, String> oldHashes, Map<String, String> newHashes) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : newHashes.entrySet()) {
            String oldHash = oldHashes.get(entry.getKey());
            if (oldHash == null || !oldHash.equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        Collections.sort(changed);
        return changed;
    }

    /**
     * Find files that existed in oldHashes but no longer exist in newHashes.
     */
    public static List<String> findDeleted(Map<String, String> oldHashes, Map<String, String> newHashes) {
        List<String> deleted = new ArrayList<>();
        for (String key : oldHashes.keySet()) {
            if (!newHashes.containsKey(key)) {
                deleted.add(key);
            }
        }
        Collections.sort(deleted);
        return deleted;
    }

    /**
     * Save hashes to a JSON file atomically (write to .tmp then rename).
     */
    public static void saveHashes(Path path, Map<String, String> hashes) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmpPath.toFile(), hashes);
        Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Load hashes from a JSON file. Returns empty map if file doesn't exist.
     */
    public static Map<String, String> loadHashes(Path path) {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (IOException e) {
            log.warn("Failed to load hashes from {}: {}", path, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    static Path resolveTildePath(String path) {
        if (path.startsWith("~/") || path.equals("~")) {
            return Path.of(System.getProperty("user.home")).resolve(path.substring(2));
        }
        return Path.of(path);
    }

    private static String hashFile(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
