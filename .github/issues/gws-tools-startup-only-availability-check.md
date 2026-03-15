# GwsTools: availability check runs only at startup — stale if gws installed/uninstalled later

## Summary

`GwsAvailabilityChecker` runs `gws --version` once at `@PostConstruct` and caches the result in a boolean `gwsAvailable` field. If the `gws` CLI is installed after Herald starts (e.g., user installs it mid-session), `GwsTools` will continue reporting "GWS CLI is not available" until Herald is restarted. Conversely, if `gws` is uninstalled, Herald will attempt to use it and get runtime errors.

## Current Implementation

```java
@PostConstruct
void checkAvailability() {
    try {
        String output = commandRunner.run("gws", "--version");
        gwsAvailable = true;
        gwsVersion = output.trim();
    } catch (Exception e) {
        gwsAvailable = false;
    }
}
```

`GwsTools` methods check `checker.isGwsAvailable()` and return an error string if false.

## Proposed Fix

### Option A: Re-check on failure (recommended)
When `gwsAvailable` is false and a tool is called, re-run the version check before returning the "not available" error. This is lazy re-detection that only costs one process spawn when the tool is actually needed.

### Option B: Periodic re-check
Add a `@Scheduled` method that re-checks every 5 minutes. More overhead but keeps the cached state fresh.

### Option C: Do nothing
This is a minor issue — `gws` installation status rarely changes at runtime. Document the restart requirement.

## Tasks

- [ ] Implement lazy re-detection on tool invocation when `gwsAvailable` is false
- [ ] Add a graceful error message when `gws` was available but the command fails at runtime
- [ ] Consider contributing availability status to a health indicator (see health indicators issue)

## References

- `herald-bot/src/main/java/com/herald/tools/GwsAvailabilityChecker.java`
- `herald-bot/src/main/java/com/herald/tools/GwsTools.java`
