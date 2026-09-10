# MCP tools in Herald

MCP is disabled by default. Enabling the Spring client alone does not grant every discovered remote tool to the model. Set `herald.mcp.allowed-tools` to the exact discovered tool names you want to allow. Empty means none; wildcards are not supported. Remote tools cannot replace local tool names. Restart after changing the configuration or allowlist.

A Spring AI compatible Streamable HTTP server can be configured in a separate Spring configuration file:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        type: SYNC
        initialized: false
        request-timeout: 5s
        streamable-http:
          connections:
            local-tools:
              url: http://127.0.0.1:3000
              endpoint: /mcp
herald:
  mcp:
    allowed-tools: fixture_echo
```

For a third-party server, substitute its address, endpoint, and actual discovered tool names (including any configured Spring AI prefix). Keep credentials in the server/transport's supported secret configuration. SSE connections remain supported, but are declared only when configured; Herald
no longer seeds invalid empty Google/Gmail transport entries. For an SSE server,
add this under `spring.ai.mcp.client` in your external configuration:

```yaml
sse:
  connections:
    local-sse:
      url: http://127.0.0.1:3001
      sse-endpoint: /sse
```

Do not put empty URL placeholders in enabled connection maps. `HERALD_MCP_CLIENT_ENABLED` and `HERALD_MCP_ALLOWED_TOOLS` provide the corresponding basic environment settings.

Herald uses the existing Spring AI MCP callback provider, preserving schemas and originating ToolContext. It discovers when assembling the configured client; disabled or empty-allowlist registration does not resolve providers. `initialized: false` avoids an eager remote handshake before Herald's discovery failure handling. Discovery failures omit the remote tools while retaining local capabilities; a model/client rebuild or restart retries discovery. Invalid transport configuration can still be a startup configuration error.

Remote tool exceptions become an explicit failed/unavailable tool result without exposing exception details to the model; cancellation and interrupted calls remain cancellations. Existing MCP elicitation handlers remain owned by their configured integration; this change does not broaden messaging recipients or implicitly approve an elicitation.

`McpClientFixtureIntegrationTest` runs a local standards-compatible Streamable HTTP server with no credentials, builds Herald's production `HeraldAgentConfig` and `AgentService`, and uses a deterministic model to request the remote tool and consume its result. It covers success and remote errors. Registry tests cover allowlisting, collisions, disabled/unavailable behavior, original context forwarding and cancellation.
