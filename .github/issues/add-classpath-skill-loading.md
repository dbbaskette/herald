# Add classpath/Spring Resource skill loading for production deployments

## Summary

Herald currently loads skills exclusively from the filesystem via `addSkillsDirectory()`. For production JAR/WAR deployments, skills should also be loadable from the classpath using Spring Resources, as recommended by the Spring AI SkillsTool documentation.

## Current Behavior

In `ReloadableSkillsTool.reload()`:
```java
this.delegate = SkillsTool.builder()
        .addSkillsDirectory(skillsDirectory)
        .build();
```

This only works when the skills directory exists on the filesystem at runtime.

## Desired Behavior

Support classpath-based skill loading via `addSkillsResource()` for packaged deployments:
```java
SkillsTool.builder()
    .addSkillsDirectory(".claude/skills")                              // local dev
    .addSkillsResource(new ClassPathResource("META-INF/skills/herald")) // from JAR
    .build()
```

## Tasks

- [ ] Add a `herald.agent.skills-classpath` property (or similar) to configure classpath skill resources
- [ ] Update `ReloadableSkillsTool` to accept both filesystem and classpath sources
- [ ] Update `HeraldAgentConfig` to wire in a `ResourceLoader` for classpath resolution
- [ ] Add integration test for classpath-based skill loading
- [ ] Document the configuration in README or CONTEXT.md

## References

- [SkillsTool docs](https://github.com/spring-ai-community/spring-ai-agent-utils/blob/main/spring-ai-agent-utils/docs/SkillsTool.md)
- [Spring AI Agent Skills blog](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/)
