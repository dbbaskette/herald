# Migrate stub FileSystemTools to spring-ai-agent-utils canonical version

## Summary

Herald's `FileSystemTools` is a self-described stub (`"Stub implementation until spring-ai-agent-utils provides the canonical version"`) with basic `file_read`, `file_write`, and `file_list` methods. The upstream `spring-ai-agent-utils` now provides a more feature-complete `FileSystemTools` with path validation, size limits, and safety checks. Herald should adopt it.

## Current Implementation

`FileSystemTools.java`:
- `file_read(String path)`: Reads entire file as UTF-8 string, no size limit
- `file_write(String path, String content)`: Creates parent directories, writes file, no safety checks
- `file_list(String path)`: Lists directory contents, no recursion option

**Missing features vs upstream:**
- No path traversal protection (e.g., `../../etc/passwd`)
- No file size limit on reads (large files could cause memory issues)
- No allowlist/denylist for writable paths
- No binary file detection

## TODO in HeraldApplication.java

```java
// TODO: Migrate stub tools to canonical spring-ai-agent-utils versions when available:
//   - FileSystemTools → upstream FileSystemTools
//   - TodoWriteTool → upstream TodoWriteTool (see #adopt-upstream-todowrite-tool)
```

## Tasks

- [ ] Check upstream `spring-ai-agent-utils` for `FileSystemTools` availability
- [ ] Replace Herald's stub with the upstream version
- [ ] Configure path restrictions (e.g., limit to `~/.herald/` and project directories)
- [ ] Configure file size limits
- [ ] Remove Herald's custom `FileSystemTools.java`
- [ ] Update `HeraldAgentConfig` wiring
- [ ] Update tests

## References

- `herald-bot/src/main/java/com/herald/tools/FileSystemTools.java`
- `herald-bot/src/main/java/com/herald/HeraldApplication.java` (TODO comment)
- [spring-ai-agent-utils GitHub](https://github.com/spring-ai-community/spring-ai-agent-utils)
