# Issue #256 — Add ToolSearchTool for Dynamic Tool Discovery

**Issue:** [dbbaskette/herald#256](https://github.com/dbbaskette/herald/issues/256)
**Status:** Design approved 2026-04-14

## Background

Herald resolves tools statically at startup. All whitelisted tools are passed to
the `ChatClient` on every request, contributing to baseline token cost per turn.
Blog 7 ([Spring AI Tool Search Tools](https://spring.io/blog/2025/12/11/spring-ai-tool-search-tools-tzolov))
introduces `ToolSearchToolCallAdvisor` — a drop-in replacement for
`ToolCallAdvisor` that indexes all registered tools and exposes a
`toolSearchTool` the model can call to discover tools by semantic query.

## Library Components

Two Maven artifacts (already in local `.m2` cache):

| Artifact | Version | Key Class |
|----------|---------|-----------|
| `org.springaicommunity:tool-search-tool` | 2.0.1 | `ToolSearchToolCallAdvisor` (extends `ToolCallAdvisor`) |
| `org.springaicommunity:tool-searcher-lucene` | 2.0.1 | `LuceneToolSearcher` (implements `ToolSearcher`) |

`ToolSearchToolCallAdvisor` extends `ToolCallAdvisor` and:
- In `doInitializeLoop`: indexes all registered `ToolCallback`s into the
  `ToolSearcher`, appends the system prompt suffix, adds `toolSearchTool` as
  an available tool
- In `doBeforeCall`: expands tool name references discovered via search into
  full tool definitions for the model
- Builder inherits `advisorOrder()` from `ToolCallAdvisor.Builder`

## What Changes

1. **Replace `ToolCallAdvisor` with `ToolSearchToolCallAdvisor`** in
   `HeraldAgentConfig.buildAdvisorChain()` — same builder pattern, same order
2. **Add `tool-searcher-lucene` dependency** to `herald-bot/pom.xml` (it
   transitively brings in `tool-search-tool`)
3. **Add version management** in parent `pom.xml`
4. **Update `activeToolNames`** to include `toolSearchTool`
5. **Update tests** to assert `ToolSearchToolCallAdvisor` instead of `ToolCallAdvisor`
