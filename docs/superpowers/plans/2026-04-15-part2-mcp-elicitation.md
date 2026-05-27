# Part 2 AskUserQuestionTool — MCP Elicitation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Spring AI MCP Client support with `@McpElicitation` bridging MCP server-driven requests to the existing `TelegramQuestionHandler`, and make adding MCP servers as easy as a YAML entry.

**Architecture:** Add `spring-ai-starter-mcp-client` to `herald-bot`. Configure MCP client SSE connections in `application.yaml` with env-var-driven URLs (disabled by default). Create a `TelegramMcpElicitationHandler` in `herald-telegram` with `@McpElicitation` that routes elicitation requests to the user via Telegram. Update `herald.yaml.example` with the new config format.

**Tech Stack:** Spring AI MCP Client, Spring Boot SSE Transport, Telegram

---

### Task 1: Add spring-ai-starter-mcp-client Dependency

**Files:**
- Modify: `herald-bot/pom.xml`

- [ ] **Step 1: Add dependency**

Add `spring-ai-starter-mcp-client` to the `dependencies` block in `herald-bot/pom.xml`, after the `tool-searcher-lucene` dependency:

```xml
        <!-- MCP Client — connects to external MCP servers (Calendar, Gmail, etc.) -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-client</artifactId>
        </dependency>
```

- [ ] **Step 2: Verify compilation**

Run: ./mvnw clean compile -pl herald-bot -am
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add herald-bot/pom.xml
git commit -m "build: add spring-ai-starter-mcp-client dependency"
```

---

### Task 2: Configure MCP Client in application.yaml

**Files:**
- Modify: `herald-bot/src/main/resources/application.yaml`
- Modify: `herald.yaml.example`

- [ ] **Step 1: Add MCP client config to application.yaml**

In `herald-bot/src/main/resources/application.yaml`, add a new `spring.ai.mcp.client` section. Place it inside the existing `spring:` block, after the `spring.ai.chat` section (around line 28). The key design: SSE connections are defined with env vars so adding a server is just setting an env var.

```yaml
    mcp:
      client:
        enabled: ${HERALD_MCP_CLIENT_ENABLED:false}
        type: SYNC
        toolcallback:
          enabled: true
        sse:
          connections:
            google-calendar:
              url: ${GCAL_MCP_URL:}
            gmail:
              url: ${GMAIL_MCP_URL:}
```

This goes inside the `spring.ai` block, at the same level as `anthropic`, `openai`, and `chat`.

- [ ] **Step 2: Update herald.yaml.example**

Replace the existing `mcp_servers` section in `herald.yaml.example` with a comment explaining the new approach:

```yaml
# MCP Servers — configure via environment variables
# Set HERALD_MCP_CLIENT_ENABLED=true and provide server URLs:
#   GCAL_MCP_URL=http://localhost:3000/sse     (Google Calendar MCP)
#   GMAIL_MCP_URL=http://localhost:3001/sse     (Gmail MCP)
# Additional MCP servers can be added in application.yaml under
# spring.ai.mcp.client.sse.connections
```

- [ ] **Step 3: Verify compilation**

Run: ./mvnw clean compile -pl herald-bot -am
Expected: SUCCESS (MCP client is disabled by default so no connection errors)

- [ ] **Step 4: Commit**

```bash
git add herald-bot/src/main/resources/application.yaml herald.yaml.example
git commit -m "feat: configure Spring AI MCP Client with SSE transport for GCal/Gmail"
```

---

### Task 3: Create TelegramMcpElicitationHandler

**Files:**
- Create: `herald-telegram/src/main/java/com/herald/telegram/TelegramMcpElicitationHandler.java`
- Create: `herald-telegram/src/test/java/com/herald/telegram/TelegramMcpElicitationHandlerTest.java`
- Modify: `herald-telegram/pom.xml`

- [ ] **Step 1: Add spring-ai-mcp dependency to herald-telegram**

The `@McpElicitation` annotation lives in `spring-ai-mcp`. Add it to `herald-telegram/pom.xml` after the `spring-ai-agent-utils` dependency:

```xml
        <!-- Spring AI MCP — for @McpElicitation annotation -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-mcp</artifactId>
        </dependency>
```

- [ ] **Step 2: Write the failing tests**

Create `herald-telegram/src/test/java/com/herald/telegram/TelegramMcpElicitationHandlerTest.java`:

```java
package com.herald.telegram;

import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TelegramMcpElicitationHandlerTest {

    @Test
    void acceptsWhenUserProvidesAnswer() {
        TelegramQuestionHandler questionHandler = mock(TelegramQuestionHandler.class);
        when(questionHandler.askQuestion(anyString())).thenReturn("I prefer large coffee");

        TelegramMcpElicitationHandler handler = new TelegramMcpElicitationHandler(questionHandler);

        ElicitRequest request = new ElicitRequest("What size coffee?", null);
        ElicitResult result = handler.handleElicitation(request);

        assertThat(result.action()).isEqualTo(ElicitResult.Action.ACCEPT);
        assertThat(result.content()).containsEntry("response", "I prefer large coffee");
    }

    @Test
    void declinesWhenUserTimesOut() {
        TelegramQuestionHandler questionHandler = mock(TelegramQuestionHandler.class);
        when(questionHandler.askQuestion(anyString())).thenReturn("");

        TelegramMcpElicitationHandler handler = new TelegramMcpElicitationHandler(questionHandler);

        ElicitRequest request = new ElicitRequest("Pick a size", null);
        ElicitResult result = handler.handleElicitation(request);

        assertThat(result.action()).isEqualTo(ElicitResult.Action.DECLINE);
    }

    @Test
    void declinesWhenUserSaysCancel() {
        TelegramQuestionHandler questionHandler = mock(TelegramQuestionHandler.class);
        when(questionHandler.askQuestion(anyString())).thenReturn("cancel");

        TelegramMcpElicitationHandler handler = new TelegramMcpElicitationHandler(questionHandler);

        ElicitRequest request = new ElicitRequest("Choose option", null);
        ElicitResult result = handler.handleElicitation(request);

        assertThat(result.action()).isEqualTo(ElicitResult.Action.DECLINE);
    }

    @Test
    void messageIncludesRequestDescription() {
        TelegramQuestionHandler questionHandler = mock(TelegramQuestionHandler.class);
        when(questionHandler.askQuestion(anyString())).thenReturn("answer");

        TelegramMcpElicitationHandler handler = new TelegramMcpElicitationHandler(questionHandler);

        ElicitRequest request = new ElicitRequest("What is your preferred language?", null);
        handler.handleElicitation(request);

        verify(questionHandler).askQuestion(argThat(msg ->
                msg.contains("What is your preferred language?")));
    }
}
```

**IMPORTANT NOTE FOR IMPLEMENTER:** The `ElicitRequest` and `ElicitResult` classes come from the MCP SDK (`io.modelcontextprotocol.spec.McpSchema`). The exact constructor signatures and field names may differ from what's shown above. After adding the dependency:
1. Check the actual class signatures by looking at the imported classes
2. Adjust the test constructors and assertions to match (e.g., the result map accessor might be `content()` or `responses()`)
3. The `ElicitResult.Action` enum values are typically `ACCEPT`, `DECLINE`, and `CANCEL`

- [ ] **Step 3: Run test to verify it fails**

Run: ./mvnw test -pl herald-telegram -Dtest=TelegramMcpElicitationHandlerTest
Expected: Compilation failure because handler class doesn't exist.

- [ ] **Step 4: Write implementation**

Create `herald-telegram/src/main/java/com/herald/telegram/TelegramMcpElicitationHandler.java`:

```java
package com.herald.telegram;

import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpElicitation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty("herald.telegram.bot-token")
public class TelegramMcpElicitationHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramMcpElicitationHandler.class);
    private final TelegramQuestionHandler questionHandler;

    public TelegramMcpElicitationHandler(TelegramQuestionHandler questionHandler) {
        this.questionHandler = questionHandler;
    }

    @McpElicitation(clients = "*")
    public ElicitResult handleElicitation(ElicitRequest request) {
        log.info("MCP Elicitation request received: {}", request.message());

        String questionText = "MCP server needs clarification:\n\n"
                + request.message()
                + "\n\nReply with your answer, or 'cancel' to decline.";

        String answer = questionHandler.askQuestion(questionText);

        if (answer == null || answer.isBlank() || "cancel".equalsIgnoreCase(answer.trim())) {
            log.info("MCP Elicitation declined or timed out");
            return new ElicitResult(ElicitResult.Action.DECLINE, null);
        }

        log.info("MCP Elicitation accepted with user response");
        return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("response", answer));
    }
}
```

**IMPORTANT NOTE FOR IMPLEMENTER:** After adding the `spring-ai-mcp` dependency:
1. Check if `@McpElicitation` supports `clients = "*"` for wildcard matching. If not, you may need to omit the `clients` param or use a specific server name.
2. Check the actual `ElicitRequest` API — the field might be `message()`, `description()`, or `requestedSchema()`. Use what's available to construct a readable question.
3. Check the `ElicitResult` constructor — it might take `(Action, Map<String, Object>)` or similar. Adjust accordingly.

- [ ] **Step 5: Run tests**

Run: ./mvnw test -pl herald-telegram -Dtest=TelegramMcpElicitationHandlerTest
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add herald-telegram/pom.xml herald-telegram/src/main/java/com/herald/telegram/TelegramMcpElicitationHandler.java herald-telegram/src/test/java/com/herald/telegram/TelegramMcpElicitationHandlerTest.java
git commit -m "feat: add MCP Elicitation handler bridging to Telegram"
```

---

### Task 4: Update Documentation

**Files:**
- Modify: `docs/herald-patterns-comparison.md`

- [ ] **Step 1: Update MCP Elicitation relationship section**

In `docs/herald-patterns-comparison.md`, find the "MCP Elicitation relationship" section (around line 120-124) and replace the Herald paragraph:

Old:
```
**Herald:** ➖ **Not Fully Implemented.** Herald uses the agent-local `AskUserQuestionTool` pattern. `@McpElicitation` / MCP server-driven elicitation is not yet implemented, but Herald's existing MCP client connections (Calendar, Gmail) could support it in future.
```

New:
```
**Herald:** ✅ **Implemented.** Herald uses the agent-local `AskUserQuestionTool` pattern and also supports `@McpElicitation` via `TelegramMcpElicitationHandler`. When an MCP server sends an elicitation request, it is routed to the user via Telegram, blocking until they answer or cancel. Spring AI MCP Client is configured with SSE transport; adding a new MCP server is a single env-var entry.
```

- [ ] **Step 2: Update summary table**

Update the Part 2 row in the Summary table:

Old:
```
| Part 2: AskUserQuestion | 4 | 4 (core, handler, async bridge, Telegram handler) | — | 1 (MCP Elicitation) |
```

New:
```
| Part 2: AskUserQuestion | 4 | 5 (core, handler, async bridge, Telegram handler, MCP Elicitation) | — | — |
```

- [ ] **Step 3: Commit**

```bash
git add docs/herald-patterns-comparison.md
git commit -m "docs: mark MCP Elicitation as implemented in Part 2 comparison"
```

---

### Task 5: Final Verification

- [ ] **Step 1: Run full test suite**

Run: ./mvnw clean test
Expected: BUILD SUCCESS with all tests passing

Note: The MCP client is disabled by default (`HERALD_MCP_CLIENT_ENABLED=false`), so no actual MCP connections will be attempted during tests.
