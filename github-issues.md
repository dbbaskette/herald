# Herald - OpenClaw Parity Issues

Below are `gh` commands to create each issue. Run them from within the herald repo.

---

## Issue 1: Integrate Model Context Protocol (MCP) Client Support

```bash
gh issue create --title "Integrate Model Context Protocol (MCP) Client Support" --body "$(cat <<'EOF'
## Summary

Herald's current skills system requires custom Java code for every new integration. Implementing a standard **Model Context Protocol (MCP) client** in `herald-core` would allow Herald to dynamically connect to any third-party MCP server (Google Search, Notion, Slack, etc.) without writing bespoke tool adapters.

## Motivation

OpenClaw relies heavily on MCP to swap tools in and out dynamically. Adding MCP client support would:
- Dramatically expand Herald's tool ecosystem with zero custom code per integration
- Align Herald with the emerging industry standard for agent-tool interoperability
- Allow users to self-serve new integrations by pointing Herald at an MCP server URL

## Proposed Approach

1. Add an MCP client library (or implement the protocol) in `herald-core`
2. Create a configuration mechanism in `herald.yaml` to register external MCP server endpoints
3. Bridge MCP-discovered tools into Herald's existing tool/skill execution pipeline
4. Ensure tool discovery, invocation, and result handling follow the MCP spec

## Acceptance Criteria

- [ ] Herald can connect to an external MCP server and discover available tools
- [ ] MCP tools can be invoked by the agent during conversations
- [ ] Tool results are properly fed back into the agent's reasoning loop
- [ ] At least one third-party MCP server (e.g., filesystem, web search) works end-to-end
- [ ] Configuration is documented in `herald.yaml.example`

## References

- [Model Context Protocol Specification](https://modelcontextprotocol.io)
- Existing skills system in `/skills/` directory
- `herald-core/src/main/java/com/herald/tools/`
EOF
)" --label "enhancement"
```

---

## Issue 2: Add Headless Browser Automation (WebTools Module)

```bash
gh issue create --title "Add Headless Browser Automation (WebTools Module)" --body "$(cat <<'EOF'
## Summary

Herald currently has CLI and filesystem tools but lacks the ability to interact with the web programmatically. A new **herald-web** or **WebTools** module using Playwright or Selenium would enable the agent to browse websites, scrape data, fill forms, and perform human-like web automation.

## Motivation

Many agent tasks require web interaction—logging into services, extracting information from pages, monitoring sites, or automating workflows that lack APIs. This is a core capability in tools like OpenClaw that Herald currently lacks.

## Proposed Approach

1. Create a new `herald-web` Maven module (or add WebTools to `herald-core`)
2. Integrate Playwright for Java (or Selenium WebDriver) for headless browser control
3. Expose tools to the agent: `navigate`, `click`, `type`, `screenshot`, `extract_text`, `wait_for`
4. Add security guardrails (domain allowlists, action rate limiting) via `ShellSecurityConfig` patterns
5. Support both headless and headed modes for debugging

## Acceptance Criteria

- [ ] Agent can navigate to a URL and extract page content
- [ ] Agent can interact with page elements (click, type, select)
- [ ] Agent can take screenshots and reason about them
- [ ] Security controls prevent unauthorized web access
- [ ] Module integrates cleanly with existing tool pipeline

## References

- [Playwright for Java](https://playwright.dev/java/)
- Existing tool framework: `herald-core/src/main/java/com/herald/tools/`
- Security config: `ShellSecurityConfig`
EOF
)" --label "enhancement"
```

---

## Issue 3: Multi-Agent Orchestration (Supervisor/Worker Pattern)

```bash
gh issue create --title "Multi-Agent Orchestration (Supervisor/Worker Pattern)" --body "$(cat <<'EOF'
## Summary

Herald's existing subagent package in `herald-core` provides basic delegation. To handle complex multi-step tasks, Herald needs a full **Supervisor/Worker orchestration pattern** where a supervisor agent can spawn specialized worker agents, coordinate their execution, and synthesize results.

## Motivation

OpenClaw spawns sub-agents for specific tasks (e.g., one agent researches while another writes). Expanding Herald's subagent system into a proper orchestration framework would enable:
- Parallel task execution (research + coding simultaneously)
- Specialized agent roles with distinct system prompts and tool access
- Complex workflows like "research a topic, draft a document, then review it"
- Better handling of long-running, multi-phase tasks

## Proposed Approach

1. Extend the existing `herald-core/agent/subagent/` package
2. Implement a Supervisor agent that can:
   - Decompose tasks into subtasks
   - Spawn Worker agents with specific roles/prompts
   - Monitor worker progress and handle failures
   - Aggregate and synthesize worker outputs
3. Add inter-agent communication (message passing or shared context)
4. Define worker archetypes (Researcher, Writer, Coder, Reviewer)
5. Ensure the orchestration works within the Telegram conversation flow

## Acceptance Criteria

- [ ] Supervisor can decompose a complex request into subtasks
- [ ] Multiple worker agents can execute in parallel
- [ ] Workers have role-specific system prompts and tool access
- [ ] Results are properly synthesized before replying to the user
- [ ] Failure in one worker doesn't crash the entire orchestration
- [ ] Works transparently through the Telegram interface

## References

- Existing subagent code: `herald-core/src/main/java/com/herald/agent/subagent/`
- Agent profiles: `herald-core/src/main/java/com/herald/agent/profile/`
EOF
)" --label "enhancement"
```

---

## Issue 4: Cross-Platform Messaging Adapters (WhatsApp, Slack, Discord)

```bash
gh issue create --title "Cross-Platform Messaging Adapters (WhatsApp, Slack, Discord)" --body "$(cat <<'EOF'
## Summary

Herald currently only supports Telegram as a messaging transport. To achieve multi-channel parity with OpenClaw, Herald needs **adapter modules** for additional platforms (WhatsApp, Slack, Discord) so the same Herald instance can be reached via different front doors.

## Motivation

Users communicate across different platforms. A multi-channel Herald would:
- Meet users where they already are (work Slack, personal WhatsApp, community Discord)
- Allow a single Herald instance with unified memory to serve across all channels
- Enable cross-platform workflows (e.g., receive a Slack alert, respond via Telegram)

## Proposed Approach

1. Extract a common `MessagingAdapter` interface from `herald-telegram`
2. Implement platform-specific modules:
   - `herald-slack` — using Slack Bolt for Java or the Slack API
   - `herald-whatsapp` — using the WhatsApp Business API / Cloud API
   - `herald-discord` — using JDA (Java Discord API)
3. Each adapter translates platform-specific events into Herald's internal message format
4. Ensure `herald-persistence` memory is shared across all channels (unified identity)
5. Add channel-aware configuration in `herald.yaml`

## Acceptance Criteria

- [ ] Common `MessagingAdapter` interface extracted from Telegram module
- [ ] At least one additional platform adapter implemented and functional
- [ ] Messages from different platforms share the same persistence/memory
- [ ] Platform-specific features (threads, reactions, rich media) are handled gracefully
- [ ] Configuration documented in `herald.yaml.example`

## References

- Existing Telegram module: `herald-telegram/`
- Persistence layer: `herald-persistence/`
- Bot entry point: `herald-bot/`
EOF
)" --label "enhancement"
```

---

## Issue 5: Enhanced Multi-Turn Reasoning Loop

```bash
gh issue create --title "Enhanced Multi-Turn Reasoning Loop" --body "$(cat <<'EOF'
## Summary

Herald currently operates in a single-turn question/answer pattern. To match OpenClaw's capabilities, Herald needs an **enhanced reasoning loop** where the agent can autonomously decide to run multiple tools across multiple turns before composing a final reply to the user.

## Motivation

Real-world tasks rarely resolve in a single tool call. An enhanced reasoning loop would allow Herald to:
- Chain multiple tool calls (search → read → analyze → respond)
- Self-correct by reviewing intermediate results and adjusting strategy
- Handle complex requests that require iterative exploration
- Implement ReAct-style (Reason + Act) patterns natively

## Proposed Approach

1. Modify the agent loop in `herald-core` to support multi-step execution
2. Implement a "think → act → observe → repeat" cycle with a configurable max-steps limit
3. Allow the agent to invoke multiple tools before generating the final user-facing response
4. Add intermediate reasoning visibility (optional verbose mode via Telegram or UI)
5. Integrate with the existing Agentic RAG capabilities for knowledge-grounded reasoning
6. Add timeout and cost-control guardrails to prevent runaway loops

## Acceptance Criteria

- [ ] Agent can autonomously chain 2+ tool calls before responding
- [ ] Agent can review tool output and decide whether to continue or respond
- [ ] Max-steps and timeout guardrails prevent infinite loops
- [ ] Intermediate steps are optionally visible in herald-ui
- [ ] Latency is acceptable for Telegram conversations (progress indicators for long tasks)
- [ ] Works with existing tool framework (FileSystem, Shell, persistence tools)

## References

- Agent core: `herald-core/src/main/java/com/herald/agent/`
- Existing tool pipeline: `herald-core/src/main/java/com/herald/tools/`
- Telegram handler: `herald-telegram/`
- UI SSE support: `herald-ui/src/main/java/com/herald/ui/sse/`
EOF
)" --label "enhancement"
```
