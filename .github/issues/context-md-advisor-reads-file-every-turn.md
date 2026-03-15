# ContextMdAdvisor reads CONTEXT.md from disk on every agent turn

## Summary

`ContextMdAdvisor` reads `~/.herald/CONTEXT.md` from the filesystem on every single agent turn. For a busy bot, this means dozens of file reads per minute for a file that changes rarely (only when the user manually edits it). This is wasteful and adds unnecessary I/O latency to every turn.

## Current Implementation

```java
@Override
public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
    String contextContent = readContextFile(); // Reads from disk every time
    if (contextContent != null && !contextContent.isBlank()) {
        // Inject into system prompt
    }
    return chain.nextAroundCall(advisedRequest);
}
```

## Proposed Fix

### Option A: WatchService-based caching (recommended)
Cache the file content and use a `WatchService` (similar to `SkillsWatcher`) to invalidate the cache when the file changes:

```java
private volatile String cachedContent;

@PostConstruct
void init() {
    cachedContent = readContextFile();
    // Register WatchService on parent directory
}
```

### Option B: Time-based cache
Re-read the file at most once every N seconds (e.g., 60s):

```java
private volatile String cachedContent;
private volatile long lastReadTime;
private static final long CACHE_TTL_MS = 60_000;
```

### Option C: Do nothing
The file read is fast (small file, OS page cache). This is a low-priority optimization.

## Tasks

- [ ] Add caching with file-change detection (WatchService or TTL)
- [ ] Ensure the cache is thread-safe (volatile or AtomicReference)
- [ ] Add test that verifies cache invalidation on file change
- [ ] Consider sharing `SkillsWatcher`'s WatchService infrastructure

## References

- `herald-bot/src/main/java/com/herald/agent/ContextMdAdvisor.java`
- `herald-bot/src/main/java/com/herald/agent/SkillsWatcher.java`
