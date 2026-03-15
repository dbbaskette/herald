# ShellSecurityConfig lacks validation — blank blocklist or zero timeout accepted silently

## Summary

`ShellSecurityConfig` is a `@ConfigurationProperties` bean that binds the shell command blocklist, timeout, and confirmation timeout from YAML. It has no `@Validated` annotation or constraint annotations (`@NotEmpty`, `@Min`, `@Positive`), so invalid configurations like an empty blocklist, zero timeout, or negative values are silently accepted, potentially disabling security protections.

## Current Implementation

```java
@ConfigurationProperties(prefix = "herald.security")
public record ShellSecurityConfig(
    List<String> blockedCommands,
    int shellTimeoutSeconds,
    int confirmationTimeoutSeconds
) {
    public ShellSecurityConfig {
        if (blockedCommands == null) blockedCommands = List.of(...defaults...);
        if (shellTimeoutSeconds <= 0) shellTimeoutSeconds = 30;
        if (confirmationTimeoutSeconds <= 0) confirmationTimeoutSeconds = 60;
    }
}
```

## Issues

1. **Blocklist defaults diverge from YAML**: The Java default list and the YAML `blocked-commands` list are maintained separately. If the YAML list is accidentally emptied, the Java defaults silently take over (good), but there's no warning that the YAML was ignored.
2. **No regex validation**: The blocklist entries are regex patterns compiled at use-time in `HeraldShellDecorator`. An invalid regex in the YAML will cause a `PatternSyntaxException` at runtime when a command is checked, not at startup.
3. **No automated bypass tests**: The code comments document known bypass vectors (backtick substitution, hex encoding, etc.) but there are no tests that verify these bypasses are caught.
4. **Timeout floor is 0**: The compact constructor prevents negative values but allows 0, which would cause immediate timeouts.

## Proposed Fix

- Add `@Validated` to the class and `@NotEmpty`, `@Min(1)` constraints
- Pre-compile regex patterns at construction time (fail-fast on bad patterns)
- Add a startup log that lists the active blocklist entries
- Add integration tests that attempt documented bypass vectors

## Tasks

- [ ] Add `@Validated` and Bean Validation constraints
- [ ] Pre-compile blocklist patterns at construction, fail-fast on invalid regex
- [ ] Add startup INFO log listing active security config
- [ ] Add bypass-vector integration tests (backtick substitution, hex encoding, etc.)
- [ ] Reconcile Java defaults with YAML defaults (single source of truth)

## References

- `herald-bot/src/main/java/com/herald/tools/ShellSecurityConfig.java`
- `herald-bot/src/main/java/com/herald/tools/HeraldShellDecorator.java`
- `herald-bot/src/main/resources/application.yaml` (herald.security section)
