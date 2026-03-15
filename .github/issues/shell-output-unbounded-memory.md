# Shell command output consumed into memory with no size limit — OOM risk

## Summary

`HeraldShellDecorator.executeCommandInternal()` reads the entire stdout and stderr of a shell command into `String` via `new String(process.getInputStream().readAllBytes())`. A command that produces large output (e.g., `cat /dev/urandom`, `find / -type f`, or a runaway log tail) will consume unbounded heap memory before the 30-second timeout fires, potentially causing an OutOfMemoryError that crashes the entire JVM.

## Current Implementation

```java
private String executeCommandInternal(String command) throws Exception {
    Process process = new ProcessBuilder("bash", "-c", command).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    boolean finished = process.waitFor(shellTimeoutSeconds, TimeUnit.SECONDS);
    // ...
}
```

`readAllBytes()` will attempt to read the entire stream into a single byte array.

## Impact

- Agent-initiated commands or user-provided commands via `shell_exec` could produce unlimited output
- OOM crashes the entire Herald process, not just the current turn
- The blocklist doesn't prevent commands that produce excessive output (e.g., `find /` is allowed)

## Proposed Fix

- Read output in a bounded buffer (e.g., first 64KB or 128KB), then truncate with a `[output truncated]` marker
- Use a `BoundedInputStream` or manual `read()` loop with a byte count limit
- Alternatively, redirect output to a temp file and read only the first N bytes

```java
byte[] buffer = new byte[MAX_OUTPUT_BYTES]; // e.g., 128 * 1024
int bytesRead = 0;
InputStream is = process.getInputStream();
int read;
while (bytesRead < buffer.length && (read = is.read(buffer, bytesRead, buffer.length - bytesRead)) != -1) {
    bytesRead += read;
}
boolean truncated = is.read() != -1;
String output = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
if (truncated) output += "\n[output truncated at " + MAX_OUTPUT_BYTES/1024 + "KB]";
```

## Tasks

- [ ] Add `MAX_OUTPUT_BYTES` constant (configurable via `herald.security.shell-max-output-bytes`)
- [ ] Replace `readAllBytes()` with bounded read loop
- [ ] Add truncation marker when output exceeds limit
- [ ] Add test with a command that produces large output
- [ ] Consider also bounding stderr independently

## References

- `herald-bot/src/main/java/com/herald/tools/HeraldShellDecorator.java`
- `herald-bot/src/main/java/com/herald/tools/ShellSecurityConfig.java`
