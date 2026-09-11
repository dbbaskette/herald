# Tanzu AI Services model binding

Herald resolves a Tanzu GenAI/AI Services binding at startup into the existing
`openai` provider. The bot JAR supports both local configuration and bindings without
rebuilding. This is model configuration support, **not complete CF deployability**.
SQLite, local files/CLIs, optional desktop capabilities and Google API runtime work
remain in [the migration plan](tanzu-platform-migration.md) and #388. Keep one bot
instance, a private bot API, and a separately authenticated public console.

## Schema and evidence

Reviewed 2026-09-11. [Broadcom article 438025](https://knowledge.broadcom.com/external/article/438025/gen-ai-tile-plan-is-renamed-to-aimodels.html)
recommends `genai` or `llm` tags across the offering/plan rename. Herald does not
match offering or plan names. Its linked [AI Services 10.3 schema guide](https://techdocs.broadcom.com/us/en/vmware-tanzu/platform/ai-services/10-3/ai/how-to-guides-discover-models-and-send-openai-requests-to-them.html)
returned HTTP 403 during implementation. The [Goose Tanzu integration guide](https://goose-docs.ai/docs/guides/tanzu-ai-services/)
(for Goose 1.28.0+) corroborates the two credential shapes and the endpoint-relative
`/openai/v1/chat/completions` route. No target foundation or broker version was
provided or tested. Confirm the installed broker's binding shape before rollout;
these are explicit supported formats, not a claim of compatibility with every broker.

Synthetic single-model example (the offering label is immaterial):

```json
{"ai-services":[{"instance_name":"herald-model","tags":["genai","llm"],
  "credentials":{"api_base":"https://models.example.test/team/openai",
    "api_key":"synthetic-example-only","model_name":"example/chat-model",
    "wire_format":"openai","model_capabilities":["chat","tools"]}}]}
```

Synthetic endpoint-only example:

```json
{"ai-services":[{"name":"herald-model","tags":["llm"],
  "credentials":{"endpoint":{"api_base":"https://models.example.test/team",
    "api_key":"synthetic-example-only"}}}]}
```

Single-model credentials require all of `api_base`, `api_key`, `model_name` and
`wire_format: openai`. They take precedence over a nested endpoint, if both exist.
A partial top-level tuple fails; fields are never mixed across formats. Explicit
embedding-only metadata or an incompatible wire format fails. Endpoint-only
credentials require `herald.genai.binding.model`; endpoint/plan names are not model
IDs. There is no remote discovery and `config_url` is never fetched. Remote chat
and tool compatibility still require an operational check.

Endpoints must be absolute HTTP(S) URLs without user information, query or fragment.
Path prefixes survive. Single-model bases get `/v1` unless already present;
endpoint-only bases get `/openai/v1`. Both yield the example request path
`/team/openai/v1/chat/completions`. [Spring AI 2.0.1 OpenAiSetup](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/setup/OpenAiSetup.java)
passes an explicit base URL through unchanged. Herald passes the resolved key
explicitly too, preventing SDK environment discovery from replacing it.

## Precedence and controls

**Selected binding → explicit environment configuration → YAML defaults.** A
binding replaces the entire group: `herald.providers.openai.base-url`,
`herald.providers.openai.api-key`, `herald.agent.model.openai`,
`herald.agent.model-catalog.openai`, and `herald.agent.default-provider=openai`.
It wins even over command-line settings for that group. The catalog contains only
the selected model, avoiding public OpenAI defaults against a private endpoint.
Local fields cannot individually override this group.

| Property | Environment alias | Default |
| --- | --- | --- |
| `herald.genai.binding.enabled` | `HERALD_GENAI_BINDING_ENABLED` | `true` |
| `herald.genai.binding.service-name` | `HERALD_GENAI_BINDING_SERVICE_NAME` | unset |
| `herald.genai.binding.model` | `HERALD_GENAI_BINDING_MODEL` | unset; required for endpoint-only |

Set `enabled=false` to select local configuration and bypass all binding parsing,
including malformed JSON. Otherwise:

- Missing/blank `VCAP_SERVICES`, `{}`, or no tagged candidates preserves local config
  when no selector was supplied.
- Exactly one candidate is validated and selected; multiple candidates fail unless
  a selector matches exactly one `instance_name` (falling back to `name` when absent).
- A supplied selector with no match, or duplicate matches, fails. First-entry
  selection is never used. Unselected credentials are not inspected.
- Invalid JSON/structure or malformed selected credentials fails startup. Error
  messages use `GENAI_*` reason codes, field names and corrective setting names;
  they omit service-provided values and parser causes. Do not print binding JSON
  or expose environment/credential actuator endpoints for troubleshooting.

An early guard prevents Spring Boot’s CF flattener from parsing credentials before
validation or opt-out, so this bot does not publish flattened `vcap.services.*`
properties. The original `VCAP_SERVICES` property is restored after that phase.
The doctor command uses the same adapter. Resolution runs at startup; restart after
local changes or restage after binding changes. Existing runtime model switches,
persisted overrides and opt-in failover may supersede the startup model. Review or
clear stale persisted overrides when moving to a different endpoint.

## Local startup

Use the existing `application.yaml` provider/model properties or environment aliases:

```sh
export HERALD_GENAI_BINDING_ENABLED=false
export OPENAI_API_KEY='your-local-provider-key'
export HERALD_PROVIDERS_OPENAI_BASE_URL=http://localhost:8000/v1
export HERALD_DEFAULT_PROVIDER=openai
export HERALD_MODEL_OPENAI=your-chat-model
java -jar herald-bot/target/herald-bot-0.4.1-SNAPSHOT.jar
```

`OPENAI_API_KEY` and canonical `HERALD_PROVIDERS_OPENAI_API_KEY` both work.
Use `HERALD_PROVIDERS_OPENAI_BASE_URL` for Herald's endpoint override. The binding
adapter leaves unbound YAML defaults unchanged. External YAML can be loaded with
`--spring.config.additional-location=file:/path/to/application.yaml`.

## CF binding example

After completing the storage/runtime prerequisites, adapting the buildpack name to
the foundation, and arranging private container networking, package the bot and use
[root manifest.yml](../manifest.yml):

```sh
bash mvnw -pl herald-bot -am package
cf push -f manifest.yml --var genai-service=existing-instance --var genai-model=example/chat-model
```

The manifest binds an **existing** instance and selects it by name. The model
variable is required by manifest interpolation; for single-model bindings it is
ignored in favor of `credentials.model_name`. No API key belongs in the manifest.
Java 21 is selected with the classic Java buildpack's JRE configuration. The bot
listens on CF's assigned `PORT`. This example introduces no cloud profile.

Alternatively, for an already pushed private bot:

```sh
cf bind-service herald-bot existing-instance
cf set-env herald-bot HERALD_GENAI_BINDING_SERVICE_NAME existing-instance
cf set-env herald-bot HERALD_GENAI_BINDING_MODEL example/chat-model
cf restage herald-bot
```

The only manifest route is `herald-bot.apps.internal`; do not add a public bot route.
Keep the public console separate, retain its authentication, and configure its
private access to the bot according to the migration plan. Binding a model does
not solve ephemeral storage, local CLI availability, or multi-instance safety.

## Verification

Synthetic fixtures live in `herald-bot/src/test/resources/genai`. Tests cover parser
selection/validation/redaction, startup registration and provider conditions,
diagnostic parity, and completion/streaming requests via WireMock. Run:

```sh
bash mvnw -pl herald-bot -am verify
python3 smoke/genai-binding.py --jar herald-bot/target/herald-bot-0.4.1-SNAPSHOT.jar
```

The smoke harness uses isolated temporary state, a loopback OpenAI stub, and the
same JAR sequentially with local configuration, a single-model binding and an
endpoint-only binding. It checks requests and the JAR's SHA-256 before/after each
run. Report synthetic results separately from a live-foundation check; neither
marks storage/runtime migration complete.

### Implementation-environment results (2026-09-11)

The 36 parser, real Spring startup safety/precedence, diagnostic parity and manifest
checks passed using direct `javac` and JUnit Launcher against the available cached
Jackson 3.0.4, Spring Boot 4.0.3 (test support 4.0.0), Spring Framework 7.0.5 and
JUnit 6.0.1 libraries. This is a partial compatibility check, not a substitute for
the repository's pinned dependency build.

The Maven wrapper could not download its uncached Maven 3.9.16 distribution.
A retry with installed Maven and a writable temporary repository also failed:
Maven Central DNS was unavailable and the required Spring Boot 4.1.1 parent was
not cached. Consequently full `verify`, Spring AI 2.0.1 request/model-wiring tests,
and packaging could not run here. The smoke script passed Python compilation and
CLI checks, but the packaged-artifact smoke remains unrun. No live-foundation test
was performed. Run both commands above in a dependency-enabled environment before
accepting deployment evidence.
