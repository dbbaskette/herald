package com.herald.tools;

import com.herald.agent.ChatChannelContext;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** All Playwright calls stay on one owner thread. No persistent profiles or user browser attachment. */
@Component
@ConditionalOnProperty(name="herald.browser.enabled",havingValue="true")
public class BrowserTools implements AutoCloseable {
    private final BrowserPolicy policy;
    private final BrowserApproval approval;
    private final String executable;
    private final boolean headless;
    private final int maxSessions;
    private final long idleMillis;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread=new Thread(r,"herald-browser"); thread.setDaemon(true); return thread;
    });
    private final Map<String,Session> sessions=new HashMap<>();
    private final ConcurrentMap<String,byte[]> screenshots=new ConcurrentHashMap<>();
    private Playwright playwright;
    private Browser browser;
    private volatile boolean closed;
    private final ThreadLocal<java.util.concurrent.atomic.AtomicBoolean> cancellation=new ThreadLocal<>();
    private static final int TIMEOUT_MS=10_000;
    private static final int BROWSER_LAUNCH_TIMEOUT_MS=30_000;
    private static class Session {
        final BrowserContext context; final Page page;
        long lastAccess=System.currentTimeMillis(); int actions; int requests; boolean mutation;
        Session(BrowserContext context,Page page) { this.context=context; this.page=page; }
    }
    @Autowired
    public BrowserTools(BrowserApproval approval,
            @Value("${herald.browser.allowed-origins:}") String origins,
            @Value("${herald.browser.executable-path:}") String executable,
            @Value("${herald.browser.headless:true}") boolean headless,
            @Value("${herald.browser.max-sessions:2}") int maxSessions,
            @Value("${herald.browser.idle-seconds:300}") long idleSeconds) {
        this(approval,origins,executable,headless,maxSessions,idleSeconds,false);
    }
    BrowserTools(BrowserApproval approval,String origins,String executable,boolean headless,int maxSessions,long idleSeconds,boolean fixtureLoopback) {
        this.approval=approval; this.policy=new BrowserPolicy(origins,fixtureLoopback); this.executable=executable;
        this.headless=headless; this.maxSessions=Math.max(1,Math.min(maxSessions,8));
        this.idleMillis=Math.max(1,Math.min(idleSeconds,1800))*1000;
        worker.scheduleWithFixedDelay(this::expire,1,1,TimeUnit.SECONDS);
    }
    @Tool(description="Open a fresh isolated browser for this conversation and navigate to an explicitly allowed HTTP(S) origin. Requires human approval. No existing cookies or user profile are loaded. Close when finished.")
    public String browser_open(String url,ToolContext context) {
        String owner=owner(context);
        return action(owner,"Visit "+url,() -> {
            policy.check(url);
            if (sessions.containsKey(owner)) throw new IllegalStateException("Close the current browser before opening another");
            if (sessions.size()>=maxSessions) throw new IllegalStateException("Browser session limit reached");
            ensureBrowser();
            BrowserContext isolated=browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(false)
                    .setServiceWorkers(ServiceWorkerPolicy.BLOCK).setViewportSize(1280,720));
            isolated.setDefaultTimeout(TIMEOUT_MS); isolated.setDefaultNavigationTimeout(TIMEOUT_MS);
            Page page=isolated.newPage(); Session session=new Session(isolated,page);
            sessions.put(owner,session);
            isolated.onPage(popup -> { if(popup!=page) popup.close(); });
            isolated.routeWebSocket("**/*",socket -> socket.close());
            page.onDialog(Dialog::dismiss); page.onDownload(Download::cancel);
            isolated.route("**/*",route -> route(session,route));
            try { checkCancelled(); page.navigate(url); return snapshot(session); }
            catch(RuntimeException e) { remove(owner); throw e; }
        });
    }
    @Tool(description="Navigate the isolated browser to an allowed URL. Requires human approval; redirects are blocked. Use observed URLs, not guessed private endpoints.")
    public String browser_navigate(String url,ToolContext context) {
        String owner=owner(context);
        return action(owner,"Navigate to "+url,() -> { policy.check(url); Session s=session(owner); checkCancelled(); s.page.navigate(url); return snapshot(s); });
    }
    @Tool(description="Read the current page URL, title and visible body text from this conversation's isolated browser.")
    public String browser_read(ToolContext context) { String owner=owner(context); return run(owner,() -> snapshot(session(owner))); }
    @Tool(description="Click one CSS selector observed on the current page. Requires human approval for this exact action. Never submit, purchase, delete or send without that approval.")
    public String browser_click(String selector,ToolContext context) { return mutate("Click",selector,null,context); }
    @Tool(description="Fill a text input selected by observed CSS selector. Requires human approval. Input can trigger remote actions; no credential/profile import is supported.")
    public String browser_type(String selector,String text,ToolContext context) { return mutate("Type",selector,text,context); }
    @Tool(description="Select a value in a select element using an observed CSS selector. Requires human approval.")
    public String browser_select(String selector,String value,ToolContext context) { return mutate("Select",selector,value,context); }
    @Tool(description="Capture the visible browser viewport as PNG. The screenshot is attached as image media to the next model request; it is not saved to disk. Requires a vision-capable model.")
    public String browser_screenshot(ToolContext context) {
        String owner=owner(context);
        return run(owner,() -> {
            Session s=session(owner); byte[] png=s.page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
            if(png.length>4*1024*1024) throw new IllegalStateException("Screenshot exceeds size limit");
            screenshots.put(owner,png); return "Browser screenshot captured and attached to the next model request.";
        });
    }
    @Tool(description="Close this conversation's browser and discard cookies, local storage and pending screenshot. Always close after completing browser work.")
    public String browser_close(ToolContext context) { String owner=owner(context); return run(owner,() -> { remove(owner); return "Browser closed."; }); }

    byte[] takeScreenshot(String conversation) { return conversation==null?null:screenshots.remove(conversation); }
    private String mutate(String operation,String selector,String value,ToolContext context) {
        String owner=owner(context);
        if(selector==null || selector.length()>500 || (value!=null && value.length()>4096)) return "Browser error: input exceeds limit";
        String url=run(owner,() -> session(owner).page.url());
        if(url.startsWith("Browser error:")) return url;
        return action(owner,operation+" on "+url+"\nSelector: "+selector+(value==null?"":"\nValue: "+value),() -> {
            Session s=session(owner);
            if(!s.page.url().equals(url)) throw new SecurityException("Page changed after approval; request a fresh action");
            s.mutation=true;
            try {
                Locator target=s.page.locator(selector);
                checkCancelled();
                switch(operation) { case "Click" -> target.click(); case "Type" -> target.fill(value); case "Select" -> target.selectOption(value); default -> throw new IllegalArgumentException(); }
                return snapshot(s);
            } finally { s.mutation=false; }
        });
    }
    private String action(String owner,String description,Callable<String> task) {
        if(!approval.approve(owner,description)) return "Browser action denied or approval unavailable.";
        return run(owner,task);
    }
    private void route(Session session,Route route) {
        try {
            checkCancelled();
            if(++session.requests>300) { route.abort(); return; }
            policy.check(route.request().url());
            if(!List.of("GET","HEAD").contains(route.request().method()) && !session.mutation) { route.abort(); return; }
            // Never follow redirect chains outside interception. Explicit navigation is required.
            APIResponse response=route.fetch(new Route.FetchOptions().setMaxRedirects(0).setTimeout(TIMEOUT_MS));
            try {
                if(response.status()>=300 && response.status()<400) route.abort();
                else { checkCancelled(); route.fulfill(new Route.FulfillOptions().setResponse(response)); }
            } finally { response.dispose(); }
        } catch(RuntimeException blocked) { try { route.abort(); } catch(RuntimeException ignored) {} }
    }
    private Session session(String owner) {
        Session s=sessions.get(owner);
        if(s==null) throw new IllegalStateException("No browser session; call browser_open");
        if(++s.actions>100) { remove(owner); throw new IllegalStateException("Session action budget exhausted"); }
        s.lastAccess=System.currentTimeMillis(); return s;
    }
    private String snapshot(Session s) {
        String text=s.page.locator("body").innerText();
        String controls=(String)s.page.evaluate("""
            () => Array.from(document.querySelectorAll('a,button,input,textarea,select')).slice(0,80)
              .map((e,i) => `:nth-match(:is(a,button,input,textarea,select), ${i+1}) | ${e.tagName} | ${e.getAttribute('aria-label') || e.innerText || e.getAttribute('placeholder') || e.getAttribute('name') || ''}`)
              .join('\\n')
            """);
        return "URL: "+s.page.url()+"\nTitle: "+s.page.title()+"\n"+text.substring(0,Math.min(text.length(),16000))
                +"\nObserved controls (CSS selector | tag | label):\n"+controls.substring(0,Math.min(controls.length(),8000));
    }
    private void ensureBrowser() {
        if(browser!=null && browser.isConnected()) return;
        if(playwright!=null) playwright.close();
        playwright=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
        var options=new BrowserType.LaunchOptions().setHeadless(headless).setChromiumSandbox(true)
                .setTimeout(BROWSER_LAUNCH_TIMEOUT_MS);
        if(!executable.isBlank()) options.setExecutablePath(Path.of(executable));
        browser=playwright.chromium().launch(options);
    }
    private String run(String owner,Callable<String> task) {
        if(closed) return "Browser error: browser service closed";
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        Future<String> future=worker.submit(() -> {
            cancellation.set(cancelled);
            try { checkCancelled(); return task.call(); }
            finally {
                cancellation.remove();
                if(cancelled.get()) remove(owner);
            }
        });
        try { return future.get(30,TimeUnit.SECONDS); }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt(); cancel(owner,cancelled,future); return "Browser error: interrupted";
        }
        catch(Exception e) {
            cancel(owner,cancelled,future); return "Browser error: operation failed (check origin, selector, installed browser and limits)";
        }
    }
    private void cancel(String owner,java.util.concurrent.atomic.AtomicBoolean cancelled,Future<?> future) {
        cancelled.set(true); future.cancel(true);
        try { worker.execute(() -> remove(owner)); } catch(RejectedExecutionException ignored) {}
    }
    private void checkCancelled() {
        var flag=cancellation.get();
        if(closed || Thread.currentThread().isInterrupted() || (flag!=null && flag.get()))
            throw new CancellationException("Browser operation cancelled");
    }
    private static String owner(ToolContext context) {
        Object value=context==null?null:context.getContext().get("chat_memory_conversation_id");
        String owner=value instanceof String s?s:ChatChannelContext.getConversationId();
        if(owner==null || owner.isBlank()) throw new IllegalStateException("Browser requires a conversation identity");
        return owner;
    }
    private void remove(String owner) {
        screenshots.remove(owner); Session session=sessions.remove(owner);
        if(session!=null) try { session.context.close(); } catch(RuntimeException ignored) {}
        if(sessions.isEmpty() && browser!=null) { try { browser.close(); } finally { browser=null; } }
    }
    private void expire() {
        long now=System.currentTimeMillis();
        for(String owner:new ArrayList<>(sessions.keySet())) if(now-sessions.get(owner).lastAccess>idleMillis) remove(owner);
    }
    @Override @PreDestroy public void close() {
        if(closed) return; closed=true;
        try { worker.submit(() -> { for(String owner:new ArrayList<>(sessions.keySet())) remove(owner); if(playwright!=null) playwright.close(); }).get(15,TimeUnit.SECONDS); }
        catch(Exception ignored) {} finally { worker.shutdownNow(); screenshots.clear(); }
    }
}
