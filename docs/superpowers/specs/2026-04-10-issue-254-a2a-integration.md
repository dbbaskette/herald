# Issue #254 — Add A2A (Agent-to-Agent) integration

**Issue:** [dbbaskette/herald#254](https://github.com/dbbaskette/herald/issues/254)
**Status:** Design approved 2026-04-10

## Background

Herald currently delegates only to local in-process subagents via the library's `ClaudeSubagentType` (a misnomer from the upstream library — the class actually accepts any Spring AI `ChatClient.Builder`, and Herald already wires Anthropic, OpenAI, Ollama, Gemini, and LM Studio models into it). Local subagent definitions live in `.claude/agents/*.md`. The blog 5 pattern introduces remote subagents via `spring-ai-agent-utils-a2a`, which lets the main agent delegate to other A2A-compliant agents over JSON-RPC.

The library provides three classes under `org.springaicommunity.agent.subagent.a2a`:

- `A2ASubagentResolver` — resolves a `SubagentReference` by fetching the remote agent's `/.well-known/agent-card.json`. Default constructor uses the standard well-known path.
- `A2ASubagentExecutor` — dispatches a task to a remote agent via the A2A JSON-RPC client transport.
- `A2ASubagentDefinition` — wraps a resolved `AgentCard`. Exposes a static `KIND` constant used to mark `SubagentReference` entries as A2A.

`TaskTool.Builder` already supports varargs `subagentTypes(...)` and `subagentReferences(...)`, so A2A is purely additive to Herald's existing wiring.

## Goal

Register one or more remote A2A agents declared in `herald.yaml` as additional
`SubagentReference` entries at startup, alongside the existing local in-process
subagents. A single `TaskTool` dispatches to either transport transparently.

## Non-goals

- Running Herald itself as an A2A **server**. Herald is purely a client.
- Authentication plumbing beyond passing an arbitrary `metadata` map verbatim
  to `SubagentReference`.
- Health checks, retries, or dynamic reload of A2A agents. A misconfigured
  URL fails on first delegation (lazy resolution); config changes require a
  restart.
- Network round-trip verification in the integration test. The test confirms
  the Spring bean graph builds with A2A config present; end-to-end delegation
  is deferred to manual smoke testing.
- Changes to `.claude/agents/` loading or `HeraldSubagentReferences`.

## Library API (verified via `javap`)

From `spring-ai-agent-utils-a2a-0.7.0.jar`:

```
public class A2ASubagentResolver implements SubagentResolver {
    public static final String WELL_KNOWN_AGENT_CARD_PATH;
    public A2ASubagentResolver();
    public A2ASubagentResolver(String wellKnownPath);
    public A2ASubagentDefinition resolve(SubagentReference);
}

public class A2ASubagentExecutor implements SubagentExecutor {
    public A2ASubagentExecutor();
    public String getKind();
    public String execute(TaskCall, SubagentDefinition);
}

public class A2ASubagentDefinition implements SubagentDefinition {
    public static final String KIND;
    public A2ASubagentDefinition(SubagentReference, io.a2a.spec.AgentCard);
    public String getName();
    public String getDescription();
    public String getKind();
    public SubagentReference getReference();
    public AgentCard getAgentCard();
}
```

From `spring-ai-agent-utils-common-0.7.0.jar`:

```
public record SubagentType(SubagentResolver resolver, SubagentExecutor executor) { String kind(); }
public record SubagentReference(String uri, String kind, Map<String,String> metadata) {
    public SubagentReference(String uri, String kind);           // 2-arg, empty metadata
    public SubagentReference(String uri, String kind, Map<...>); // 3-arg
}
```

From `spring-ai-agent-utils-0.7.0.jar`:

```
public class TaskTool$Builder {
    Builder subagentReferences(List<SubagentReference>);
    Builder subagentReferences(SubagentReference...);
    Builder subagentTypes(List<SubagentType>);
    Builder subagentTypes(SubagentType...);
    ...
}
```

`A2ASubagentResolver` does **not** resolve at construction time. It is only
invoked when `TaskTool` receives a delegation call. Startup with an
unreachable URL is therefore safe.

## Architecture

### Data flow

```
herald.yaml
  └─ herald.a2a.agents[] ────────┐
                                 ▼
HeraldConfig.A2a   ──────────►  HeraldConfig.a2aAgents() : List<A2aAgent>
                                 │
                                 ▼
HeraldAgentConfig.modelSwitcher
  │
  ├─ existing local SubagentType build (ClaudeSubagentType — unchanged)
  ├─ existing loadSubagentReferences(agentsDirectory)  (unchanged)
  │
  └─ if (!a2aAgents.isEmpty())
       ├─ a2aType = new SubagentType(new A2ASubagentResolver(), new A2ASubagentExecutor())
       ├─ register (local subagentType, a2aType) via .subagentTypes(...)
       └─ for each A2aAgent → new SubagentReference(url, A2ASubagentDefinition.KIND, metadata)
             └─ append to the combined references list passed to .subagentReferences(...)
```

## Dependencies

Add to parent `pom.xml` inside `<dependencyManagement>`, alongside the
existing `spring-ai-agent-utils` entry:

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agent-utils-a2a</artifactId>
    <version>0.7.0</version>
</dependency>
```

Add to `herald-bot/pom.xml` as a regular compile-scope dependency (no
`<version>` tag — inherited):

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agent-utils-a2a</artifactId>
</dependency>
```

This transitively adds (from the artifact's published pom):

- `io.github.a2asdk:a2a-java-sdk-client:0.3.3.Final`
- `io.github.a2asdk:a2a-java-sdk-client-transport-jsonrpc:0.3.3.Final`
- `org.springaicommunity:spring-ai-agent-utils-common:0.7.0` (already present)

Add WireMock to `herald-bot/pom.xml` as a test-scope dependency:

```xml
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.9.1</version>
    <scope>test</scope>
</dependency>
```

## Configuration

### `herald-core/src/main/java/com/herald/config/HeraldConfig.java`

Add two new records inside `HeraldConfig`:

```java
public record A2a(List<A2aAgent> agents) {}

public record A2aAgent(String name, String url, Map<String, String> metadata) {}
```

Add `A2a a2a` to the top-level `HeraldConfig` record parameter list (after
`longTermMemory`):

```java
public record HeraldConfig(Memory memory, Telegram telegram, Agent agent, Providers providers, Cron cron,
                           Weather weather, Obsidian obsidian, Vault vault, Archival archival,
                           LongTermMemory longTermMemory, A2a a2a) {
    ...
}
```

Add a null-safe accessor near the bottom of the class:

```java
public List<A2aAgent> a2aAgents() {
    if (a2a != null && a2a.agents() != null) {
        return a2a.agents();
    }
    return List.of();
}
```

Add the necessary imports: `java.util.List`, `java.util.Map`. (`List` may
already be imported; verify with grep.)

### Example `herald.yaml`

```yaml
herald:
  a2a:
    agents:
      - name: airbnb-agent
        url: http://localhost:10001/airbnb
        metadata:
          authorization: "Bearer some-token"
      - name: weather-agent
        url: http://localhost:10002/weather
```

`name` is a local label used only in startup log output. The real
display name is sourced from the resolved `AgentCard`. `metadata` is
optional — if omitted, the code uses an empty map.

## `HeraldAgentConfig.modelSwitcher` changes

New imports:

```java
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentDefinition;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentExecutor;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentResolver;
import java.util.ArrayList;
```

(`SubagentReference` is already imported via existing usage of
`loadSubagentReferences`; verify and skip the import if already present.
`ArrayList` may also already be present.)

Replace the current `taskToolBuilder` block (approx. lines 207-215):

```java
        var subagentRefs = loadSubagentReferences(agentsDirectory);

        var taskToolBuilder = TaskTool.builder()
                .subagentTypes(subagentType)
                .taskRepository(taskRepository);

        if (!subagentRefs.isEmpty()) {
            taskToolBuilder.subagentReferences(subagentRefs);
        }
```

with this expanded block:

```java
        var subagentRefs = loadSubagentReferences(agentsDirectory);
        var a2aAgents = config.a2aAgents();

        var taskToolBuilder = TaskTool.builder().taskRepository(taskRepository);

        if (a2aAgents.isEmpty()) {
            taskToolBuilder.subagentTypes(subagentType);
            if (!subagentRefs.isEmpty()) {
                taskToolBuilder.subagentReferences(subagentRefs);
            }
        } else {
            var a2aType = new SubagentType(new A2ASubagentResolver(), new A2ASubagentExecutor());
            taskToolBuilder.subagentTypes(subagentType, a2aType);

            var combinedRefs = new ArrayList<>(subagentRefs);
            for (var agent : a2aAgents) {
                Map<String, String> metadata = agent.metadata() != null ? agent.metadata() : Map.of();
                combinedRefs.add(new SubagentReference(agent.url(), A2ASubagentDefinition.KIND, metadata));
            }
            taskToolBuilder.subagentReferences(combinedRefs);
            log.info("Registered {} A2A agent(s) alongside {} local subagent(s)",
                    a2aAgents.size(), subagentRefs.size());
        }
```

The existing `subagentType` local (the local in-process subagent type) keeps
its name — no rename needed. The new variable is `a2aType`. The `log` field
already exists on `HeraldAgentConfig`.

## Tests

### Unit test — `HeraldConfigA2aTest`

New file: `herald-bot/src/test/java/com/herald/config/HeraldConfigA2aTest.java`

```java
package com.herald.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldConfigA2aTest {

    @Test
    void a2aAgentsReturnsConfiguredListWhenPresent() {
        var agents = List.of(
                new HeraldConfig.A2aAgent("airbnb", "http://localhost:10001/airbnb",
                        Map.of("authorization", "Bearer token")),
                new HeraldConfig.A2aAgent("weather", "http://localhost:10002/weather", null)
        );
        HeraldConfig config = configWithA2a(new HeraldConfig.A2a(agents));

        assertThat(config.a2aAgents()).hasSize(2);
        assertThat(config.a2aAgents().get(0).name()).isEqualTo("airbnb");
        assertThat(config.a2aAgents().get(0).url()).isEqualTo("http://localhost:10001/airbnb");
        assertThat(config.a2aAgents().get(0).metadata()).containsEntry("authorization", "Bearer token");
        assertThat(config.a2aAgents().get(1).metadata()).isNull();
    }

    @Test
    void a2aAgentsReturnsEmptyListWhenA2aIsNull() {
        HeraldConfig config = configWithA2a(null);
        assertThat(config.a2aAgents()).isEmpty();
    }

    @Test
    void a2aAgentsReturnsEmptyListWhenAgentsListIsNull() {
        HeraldConfig config = configWithA2a(new HeraldConfig.A2a(null));
        assertThat(config.a2aAgents()).isEmpty();
    }

    private HeraldConfig configWithA2a(HeraldConfig.A2a a2a) {
        return new HeraldConfig(null, null, null, null, null, null, null, null, null, null, a2a);
    }
}
```

### Integration test — `HeraldA2aIntegrationTest`

New file: `herald-bot/src/test/java/com/herald/agent/HeraldA2aIntegrationTest.java`

Purpose: prove that a non-empty `herald.a2a.agents` config causes
`modelSwitcher` to build a `TaskTool` with an A2A `SubagentType` and the
expected `SubagentReference` entries, without executing a real JSON-RPC
round trip.

Approach:

1. Start a WireMock server on a dynamic port using
   `com.github.tomakehurst.wiremock.junit5.WireMockExtension`.
2. Stub `GET /testagent/.well-known/agent-card.json` with a minimal valid
   `AgentCard` JSON body — captured inline in the test as a Java text block.
3. Use the existing `HeraldAgentConfigIntegrationTest` setup pattern
   (mock `ChatModel`, `ChatMemory`, etc.) to invoke `modelSwitcher` with a
   `HeraldConfig` whose `a2a.agents[0].url` points at the WireMock server.
4. Assert the returned `ModelSwitcher` is non-null.
5. Assert an info log line containing `"Registered 1 A2A agent"` was emitted,
   using Spring Boot's `OutputCaptureExtension`.

Minimal agent-card fixture (subject to adjustment based on A2A SDK schema):

```json
{
  "name": "Test Agent",
  "description": "Integration test agent",
  "url": "http://localhost:{port}/testagent",
  "version": "1.0.0",
  "capabilities": {},
  "skills": [],
  "defaultInputModes": ["text"],
  "defaultOutputModes": ["text"]
}
```

If deserialization fails during test execution, iterate on the fixture
until the A2A SDK's Jackson reader accepts it — treat this as a known
edge case rather than a blocker.

The test deliberately does **not** assert that a task was dispatched to
WireMock. Real dispatch requires a working JSON-RPC server mock, which is
outside scope. The test's value is proving the Spring context wiring is
valid when A2A config is present.

## Documentation

- `README.md` — add a short subsection under the existing Agent/Skills area
  describing how to configure A2A agents. Include the yaml snippet from the
  "Example `herald.yaml`" section above and a one-line note that resolution
  is lazy (no startup round trip).
- `docs/module-inventory.md` — no changes. No new Herald classes are added;
  only config records, which the inventory does not track.

## Risks

1. **WireMock / A2A SDK schema compatibility.** The minimal `AgentCard` JSON
   may need additional required fields. Mitigation: iterate on the fixture
   when the test runs; document the final shape inline.
2. **Transitive dependency conflicts.** `a2a-java-sdk-client` may pull in
   Jackson or other libraries at versions that clash with Spring Boot's
   managed versions. Mitigation: run
   `./mvnw -pl herald-bot dependency:tree | grep -E 'jackson|conflict'`
   after adding the dep; apply `<exclusions>` if needed.
3. **Fat-jar size growth.** The new transitive deps add approximately
   2-3 MB to `herald-bot-*.jar`. Acceptable for a personal bot.
4. **Lazy resolution UX.** A misconfigured or unreachable A2A URL does not
   fail at startup; the failure surfaces only on first delegation. The
   `log.info` line in `modelSwitcher` makes registration visible, but
   operators should know that "app started" does not imply "A2A is reachable".

## Verification

- Build affected modules together:
  `./mvnw -pl herald-bot,herald-core,herald-telegram -am verify`
  Expect BUILD SUCCESS, all tests green.
- Grep for `A2ASubagent` or `a2aAgents` to confirm the new symbols appear
  only where expected: `HeraldConfig.java`, `HeraldAgentConfig.java`, the
  two test files, and the spec/plan docs.
- Manual smoke (optional, requires a running A2A server): configure one
  agent in `herald.yaml`, start herald-bot, delegate a task via
  `Task(subagent_type="<remote-name>", prompt="...")`, confirm the remote
  agent is invoked.

## Follow-up issues (out of scope for #254)

Captured here for the audit trail — these are intentionally **not** part of
this spec:

- **Claude Code CLI provider (`claude -p`).** Add a new `ChatModel`
  implementation that shells out to the `claude` CLI in headless mode.
  Distinct from the existing Anthropic API provider.
- **Multi-provider env/docs audit.** Ensure every provider has documented
  env vars for key + default model and consistent precedence/fallback
  behavior. Today's `ModelSwitcher` already supports anthropic, openai,
  ollama, gemini, and lmstudio via `herald.agent.default-provider`, but the
  documentation and env surface could be tighter.
- **Cloud Foundry / Tanzu GenAI service binding.** Parse `VCAP_SERVICES` at
  startup to pull GenAI credentials and endpoints from a bound service
  (via `cf bind-service` or manifest), so the same jar works locally (env
  vars) and on TAS (service binding).

Each of these deserves its own issue and its own spec → plan → execute cycle.
