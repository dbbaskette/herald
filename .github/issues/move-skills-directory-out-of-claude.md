# Move skills directory from .claude/skills to skills/

## Summary

Herald's skills currently live in `.claude/skills/`, a convention inherited from Claude Code. Since Herald uses the model-agnostic Spring AI `SkillsTool` and isn't tied to Claude, the directory should be renamed to something neutral like `skills/`.

## Changes Required

- [ ] Move `.claude/skills/*` to `skills/`
- [ ] Update default value in `HeraldAgentConfig`: `@Value("${herald.agent.skills-directory:skills}")`
- [ ] Update default value in `SkillsWatcher`: `@Value("${herald.agent.skills-directory:skills}")`
- [ ] Update any references in tests, CONTEXT.md, or documentation
- [ ] Remove `.claude/` directory if nothing else remains in it
