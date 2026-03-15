# Add MCP (Model Context Protocol) server integration for extensible tooling

## Summary

Herald currently defines all tools as Spring `@Tool`-annotated beans compiled into the application. The Model Context Protocol (MCP) provides a standardized way to expose and consume tools via a client-server protocol, enabling dynamic tool discovery and integration with external tool servers without recompiling Herald. Spring AI has first-class MCP support that Herald should leverage.

## Why MCP

1. **Dynamic tool discovery**: Add new tools by starting an MCP server, no Herald rebuild needed
2. **Language-agnostic tools**: Tools can be implemented in Python, Node.js, Go, etc.
3. **Ecosystem access**: Growing library of pre-built MCP servers (filesystem, databases, APIs, web search)
4. **Separation of concerns**: Heavy or specialized tools run in their own process with independent resource limits
5. **Security isolation**: MCP servers run as separate processes with their own permissions

## Spring AI MCP Support

Spring AI provides `spring-ai-starter-mcp-client` which auto-configures MCP client connections:

```yaml
spring:
  ai:
    mcp:
      clients:
        - name: filesystem
          transport: stdio
          command: npx
          args: ["-y", "@anthropic/mcp-server-filesystem", "/home/user"]
        - name: web-search
          transport: sse
          url: http://localhost:3001/sse
```

MCP tools are automatically registered as Spring AI `ToolCallback` instances and can be added to the `ChatClient` tool list alongside Herald's built-in tools.

## Integration Points

- `HeraldAgentConfig`: Add MCP tool callbacks to the `clientBuilderFactory` tool list
- `ReloadableSkillsTool`/`SkillsWatcher`: Consider MCP as an alternative to filesystem-based skills
- `CommandHandler`: Add `/mcp [list|status]` command to show connected MCP servers
- `application.yaml`: MCP client configuration section

## Tasks

- [ ] Add `spring-ai-starter-mcp-client` dependency to `herald-bot/pom.xml`
- [ ] Configure MCP client connections in `application.yaml`
- [ ] Register MCP tool callbacks in `HeraldAgentConfig`
- [ ] Add `/mcp` admin command to list connected servers and available tools
- [ ] Add health indicator for MCP server connectivity
- [ ] Document how to add custom MCP servers
- [ ] Add integration test with a mock MCP server

## References

- [Spring AI MCP documentation](https://docs.spring.io/spring-ai/reference/api/mcp.html)
- [MCP specification](https://modelcontextprotocol.io)
- `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`
- `herald-bot/pom.xml`
