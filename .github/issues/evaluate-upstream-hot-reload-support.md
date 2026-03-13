# Evaluate upstream hot-reload support vs Herald's ReloadableSkillsTool

## Summary

Evaluate whether Herald's custom `ReloadableSkillsTool` wrapper and `SkillsWatcher` filesystem watcher are still necessary, or if upstream `spring-ai-agent-utils` has added (or plans to add) hot-reload support that could replace them.

## Context

Herald maintains two custom classes to support hot-reloading skills from disk:

- **`ReloadableSkillsTool`** — a `ToolCallback` wrapper that rebuilds the inner `SkillsTool` delegate on `reload()`, using a volatile swap for thread safety
- **`SkillsWatcher`** — a `WatchService`-based filesystem monitor with debounced reload (250ms)

These work well but represent Herald-specific code that must be maintained separately from the upstream library. If `spring-ai-agent-utils` adds similar functionality, Herald should adopt it to reduce maintenance burden.

## Tasks

- [ ] Check upstream `spring-ai-agent-utils` for any hot-reload support or open issues/PRs
- [ ] If upstream support exists or is imminent, plan migration
- [ ] If not, consider contributing Herald's implementation upstream as a PR
- [ ] Document the decision

## References

- [spring-ai-agent-utils GitHub](https://github.com/spring-ai-community/spring-ai-agent-utils)
- Herald's `ReloadableSkillsTool.java` and `SkillsWatcher.java`
