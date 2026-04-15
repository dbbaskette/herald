# Blog 1 Gaps — Classpath Skill Packaging + Anthropic Native Skills

**Issue:** [dbbaskette/herald#250](https://github.com/dbbaskette/herald/issues/250) (follow-up)
**Status:** Design approved 2026-04-14

## Gap 1: Classpath/JAR Skill Packaging

**Blog pattern:** `SkillsTool.builder().addSkillsResource(Resource)` loads skills from
Spring `Resource` abstraction — classpath, JAR, URL. Enables bundling skills inside
the application JAR for deployment without a separate `skills/` directory.

**Current state:** `ReloadableSkillsTool` already accepts `List<Resource>` in its
constructor and calls `builder.addSkillsResources()`. But no classpath resources
are wired — the `@Bean` in `HeraldAgentConfig` only passes the filesystem directory.

**What to do:**
1. Add a `herald.agent.skills-classpath` config property (comma-separated classpath patterns)
2. Wire `ResourcePatternResolver` in `HeraldAgentConfig` to resolve patterns to `Resource[]`
3. Pass resolved resources to `ReloadableSkillsTool(directory, resources)`
4. Bundle a sample skill as a classpath resource in `herald-core/src/main/resources/skills/`

## Gap 2: Anthropic Native Skills API

**Blog pattern:** Anthropic's cloud-sandboxed document generation skills (XLSX, DOCX,
PPTX, PDF). Set via `AnthropicChatOptions.builder().skill(AnthropicSkill.XLSX)`.
Claude generates documents in a server-side container; `AnthropicSkillsResponseHelper`
downloads the results.

**Current state:** Spring AI 2.0.0-SNAPSHOT ships `AnthropicSkill`, `AnthropicSkillRecord`,
`AnthropicSkillContainer`, and `AnthropicSkillsResponseHelper`. Herald does not wire any
of them. `ModelSwitcher.chatOptionsForModel()` builds `AnthropicChatOptions` with only
the model ID.

**What to do:**
1. Add a `herald.agent.anthropic-skills` config property (list of skill IDs: xlsx, docx, pptx, pdf)
2. In `ModelSwitcher.chatOptionsForModel()`, when building `AnthropicChatOptions`, add
   skills from config
3. The model will automatically use them when asked to create documents — no tool
   registration needed
4. Add a convenience method or note about `AnthropicSkillsResponseHelper.downloadAllFiles()`
   for extracting generated files from responses
