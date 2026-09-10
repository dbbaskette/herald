package com.herald.tools;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit browser opt-in. All HTTP traffic stays on in-process loopback fixtures; no user profile. */
@EnabledIfSystemProperty(named="herald.browser.fixture-executable",matches=".+")
class BrowserFixtureTest {
    HttpServer fixture,forbidden;
    BrowserTools tools;
    BrowserApproval approval;
    String origin;
    AtomicInteger forbiddenHits=new AtomicInteger();
    ToolContext context=new ToolContext(Map.of("chat_memory_conversation_id","fixture-agent"));
    @BeforeEach void setup() throws Exception {
        forbidden=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        forbidden.createContext("/",exchange -> { forbiddenHits.incrementAndGet(); exchange.sendResponseHeaders(200,0); exchange.close(); });
        forbidden.start();
        fixture=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        fixture.createContext("/redirect",exchange -> {
            exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+forbidden.getAddress().getPort()+"/");
            exchange.sendResponseHeaders(302,-1); exchange.close();
        });
        fixture.createContext("/",exchange -> {
            byte[] body="""
                <html><head><title>Herald browser fixture</title></head><body>
                <h1>Local fixture only</h1><input id="name" aria-label="Name">
                <select id="choice"><option value="a">Alpha</option><option value="b">Beta</option></select>
                <button id="apply" onclick="document.querySelector('#result').textContent=document.querySelector('#name').value+':'+document.querySelector('#choice').value">Apply</button>
                <p id="result">Nothing yet</p>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","text/html"); exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        fixture.start(); origin="http://127.0.0.1:"+fixture.getAddress().getPort();
        approval=mock(BrowserApproval.class); when(approval.approve(anyString(),anyString())).thenReturn(true);
        tools=new BrowserTools(approval,origin,System.getProperty("herald.browser.fixture-executable"),true,2,60,true);
    }
    @AfterEach void cleanup() { if(tools!=null)tools.close(); if(fixture!=null)fixture.stop(0); if(forbidden!=null)forbidden.stop(0); }
    @Test void actualAgentToolLoopInteractsAndReceivesPngAsModelMedia() {
        ChatModel model=mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        String[][] steps={
            {"browser_open","{\"url\":\""+origin+"/\"}"},
            {"browser_type","{\"selector\":\"#name\",\"text\":\"Written fixture\"}"},
            {"browser_select","{\"selector\":\"#choice\",\"value\":\"b\"}"},
            {"browser_click","{\"selector\":\"#apply\"}"},
            {"browser_screenshot","{}"}
        };
        AtomicInteger calls=new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            int step=calls.getAndIncrement(); Prompt prompt=invocation.getArgument(0);
            if(step<steps.length) return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("fixture-call-"+step,"function",steps[step][0],steps[step][1]))).build())));
            assertThat(prompt.getInstructions().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .flatMap(m -> m.getMedia().stream())).anySatisfy(media -> {
                        assertThat(media.getMimeType().toString()).isEqualTo("image/png");
                        assertThat(media.getDataAsByteArray()).hasSizeGreaterThan(100);
                    });
            assertThat(tools.browser_read(context)).contains("Written fixture:b");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Fixture browser interaction complete"))));
        });
        var advisors=new ArrayList<>(com.herald.agent.ExecutionAdvisors.create(com.herald.agent.ExecutionLimits.defaults(),() -> {}));
        advisors.add(new BrowserScreenshotAdvisor(tools));
        String result=ChatClient.builder(model).defaultTools(tools).defaultAdvisors(advisors).build()
                .prompt().user("Use the local fixture browser tools, then inspect the screenshot.")
                .toolContext(Map.of("chat_memory_conversation_id","fixture-agent"))
                .advisors(a -> a.param("chat_memory_conversation_id","fixture-agent")).call().content();
        assertThat(result).isEqualTo("Fixture browser interaction complete");
        assertThat(calls.get()).isEqualTo(6);
        verify(approval,times(4)).approve(eq("fixture-agent"),anyString());
        assertThat(tools.browser_close(context)).contains("closed");
        assertThat(tools.browser_read(context)).startsWith("Browser error:");
    }
    @Test void enforcesDeniedActionsOriginsRedirectsAndConversationIsolation() {
        assertThat(tools.browser_open(origin,context)).contains("Local fixture only");
        when(approval.approve(anyString(),anyString())).thenReturn(false);
        assertThat(tools.browser_click("#apply",context)).contains("denied");
        assertThat(tools.browser_read(new ToolContext(Map.of("chat_memory_conversation_id","other")))).startsWith("Browser error:");
        when(approval.approve(anyString(),anyString())).thenReturn(true);
        assertThat(tools.browser_navigate(origin+"/redirect",context)).startsWith("Browser error:");
        assertThat(tools.browser_navigate("http://127.0.0.1:"+forbidden.getAddress().getPort(),context)).startsWith("Browser error:");
        assertThat(forbiddenHits.get()).isZero();
        tools.browser_close(context);
        assertThat(tools.browser_open(origin,context)).contains("Nothing yet");
    }
    @Test void idleSessionExpiresAndMissingExecutableFailsWithoutPoisoningService() throws Exception {
        tools.close();
        tools=new BrowserTools(approval,origin,System.getProperty("herald.browser.fixture-executable"),true,1,1,true);
        assertThat(tools.browser_open(origin,context)).contains("Local fixture only");
        Thread.sleep(2500);
        assertThat(tools.browser_read(context)).startsWith("Browser error:");
        tools.close();
        tools=new BrowserTools(approval,origin,"/nonexistent/fixture-browser",true,1,1,true);
        assertThat(tools.browser_open(origin,context)).startsWith("Browser error:");
        assertThat(tools.browser_read(context)).startsWith("Browser error:");
    }
}
