# Add allowed-tools and model frontmatter to existing skills

## Summary

The existing Herald skills in `.claude/skills/` do not use the `allowed-tools` or `model` YAML frontmatter fields supported by `SkillsTool`. Adding these would improve skill behavior by pre-authorizing tools (avoiding permission prompts) and allowing per-skill model overrides.

## Current State

Herald has 6 skills (weather, gmail, github, google-calendar, obsidian, broadcom), all with only `name` and `description` in their frontmatter.

## Desired State

Add `allowed-tools` to skills that need specific tools. For example:

```yaml
---
name: weather
description: >
  Looks up current weather conditions...
allowed-tools: shell
---
```

And optionally `model` for skills that could use a cheaper/faster model:

```yaml
---
name: broadcom
description: >
  Broadcom/VMware Tanzu knowledge base...
model: haiku
---
```

## Tasks

- [ ] Audit each skill and determine which tools it needs (`shell`, `filesystem`, `web`, etc.)
- [ ] Add `allowed-tools` frontmatter to each skill's SKILL.md
- [ ] Evaluate which skills could benefit from a `model` override
- [ ] Add tests to verify frontmatter is parsed correctly

## References

- [SkillsTool docs](https://github.com/spring-ai-community/spring-ai-agent-utils/blob/main/spring-ai-agent-utils/docs/SkillsTool.md)
