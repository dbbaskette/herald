# FileSystemTools has no path traversal protection — agent can read/write any file

## Summary

Herald's stub `FileSystemTools` performs no path validation. The `file_read` and `file_write` tools accept any absolute or relative path, meaning the agent can read sensitive files (e.g., `/etc/shadow`, `~/.ssh/id_rsa`, `.env`) or write to system locations. While the agent's system prompt includes safety instructions, tool-level guardrails should not rely solely on prompt compliance.

## Current Implementation

```java
@Tool(description = "Read file contents")
public String file_read(String path) {
    return Files.readString(Path.of(path));
}

@Tool(description = "Write content to a file, creating parent directories if needed")
public String file_write(String path, String content) {
    Path filePath = Path.of(path);
    Files.createDirectories(filePath.getParent());
    Files.writeString(filePath, content);
    return "Written to " + path;
}
```

## Risks

1. **Path traversal**: `../../etc/passwd` or absolute paths like `/etc/shadow` are accepted
2. **Sensitive file reads**: `.env`, credentials, SSH keys, Herald's own DB
3. **System file writes**: Agent could write to `/etc/cron.d/`, `.bashrc`, etc.
4. **Directory creation**: `file_write` creates parent directories, enabling writes to new system locations
5. **No file size limit**: Large file reads could cause memory issues

## Proposed Fix

- Define an allowlist of base directories (configurable):
  ```yaml
  herald:
    tools:
      file-system:
        allowed-paths:
          - ~/.herald/
          - ~/projects/
        max-read-size: 1048576  # 1MB
  ```
- Resolve and canonicalize paths before checking against allowlist
- Reject paths containing `..` after canonicalization
- Add file size limit for reads
- Deny access to dotfiles and known sensitive patterns (`.env`, `.ssh`, `.git/config`)

## Tasks

- [ ] Add path allowlist configuration
- [ ] Implement path canonicalization and traversal check
- [ ] Add file size limit for reads
- [ ] Add sensitive file pattern denylist
- [ ] Add tests for traversal attempts and edge cases
- [ ] Consider adopting upstream FileSystemTools if available (see migrate-stub-filesystem-tools issue)

## References

- `herald-bot/src/main/java/com/herald/tools/FileSystemTools.java`
- OWASP Path Traversal: https://owasp.org/www-community/attacks/Path_Traversal
