package com.herald.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebToolsTest {

    private WebTools toolsWithFetcher(WebTools.HttpFetcher fetcher) {
        return new WebTools("", fetcher, (q, k) -> new WebTools.HttpResult(200, "{}", "application/json"));
    }

    private WebTools toolsWithSearcher(WebTools.SearchFetcher searcher) {
        return new WebTools("test-api-key", url -> new WebTools.HttpResult(200, "", "text/html"), searcher);
    }

    // --- web_fetch tests ---

    @Test
    void fetchReturnsStrippedHtmlContent() {
        WebTools tools = toolsWithFetcher(url ->
                new WebTools.HttpResult(200,
                        "<html><head><title>Test</title></head><body><p>Hello World</p></body></html>",
                        "text/html"));

        String result = tools.web_fetch("https://example.com");
        assertThat(result).contains("Hello World");
        assertThat(result).doesNotContain("<p>");
        assertThat(result).doesNotContain("<html>");
    }

    @Test
    void fetchReturnsErrorForEmptyUrl() {
        WebTools tools = toolsWithFetcher(url -> new WebTools.HttpResult(200, "ok", "text/html"));

        String result = tools.web_fetch("");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("must not be empty");
    }

    @Test
    void fetchReturnsErrorForInvalidScheme() {
        WebTools tools = toolsWithFetcher(url -> new WebTools.HttpResult(200, "ok", "text/html"));

        String result = tools.web_fetch("ftp://example.com");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("http://");
    }

    @Test
    void fetchReturnsErrorForHttpError() {
        WebTools tools = toolsWithFetcher(url ->
                new WebTools.HttpResult(404, "Not Found", "text/html"));

        String result = tools.web_fetch("https://example.com/missing");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("HTTP 404");
    }

    @Test
    void fetchReturnsErrorForUnsupportedContentType() {
        WebTools tools = toolsWithFetcher(url ->
                new WebTools.HttpResult(200, "binary", "application/octet-stream"));

        String result = tools.web_fetch("https://example.com/file.bin");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("Unsupported content type");
    }

    @Test
    void fetchRejectsNonHtmlTextContent() {
        WebTools tools = toolsWithFetcher(url ->
                new WebTools.HttpResult(200, "plain text", "text/plain"));

        String result = tools.web_fetch("https://example.com/file.txt");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("Only text/html is supported");
    }

    @Test
    void fetchTruncatesLongContent() {
        String longContent = "<html><body>" + "x".repeat(60_000) + "</body></html>";
        WebTools tools = toolsWithFetcher(url ->
                new WebTools.HttpResult(200, longContent, "text/html"));

        String result = tools.web_fetch("https://example.com");
        assertThat(result).contains("[Content truncated at 50000 characters]");
    }

    @Test
    void fetchHandlesExceptions() {
        WebTools tools = toolsWithFetcher(url -> {
            throw new RuntimeException("Connection refused");
        });

        String result = tools.web_fetch("https://example.com");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("Connection refused");
    }

    @Test
    void fetchAllowsEmptyContentType() {
        WebTools tools = toolsWithFetcher(url ->
                new WebTools.HttpResult(200, "<html><body>Content</body></html>", ""));

        String result = tools.web_fetch("https://example.com");
        // Empty content type should be treated as HTML-like since body starts with <
        assertThat(result).contains("Content");
    }

    @Test
    void fetchReturnsErrorForEmptyBody() {
        WebTools tools = toolsWithFetcher(url ->
                new WebTools.HttpResult(200, "", "text/html"));

        String result = tools.web_fetch("https://example.com");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("Empty response");
    }

    // --- SSRF protection tests ---

    @Test
    void fetchBlocksLocalhostUrls() {
        WebTools tools = toolsWithFetcher(url ->
                new WebTools.HttpResult(200, "secret", "text/html"));

        String result = tools.web_fetch("http://localhost:8080/actuator/env");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("internal/private network");
    }

    @Test
    void fetchBlocksLoopbackIp() {
        WebTools tools = toolsWithFetcher(url ->
                new WebTools.HttpResult(200, "secret", "text/html"));

        String result = tools.web_fetch("http://127.0.0.1:8080/actuator/env");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("internal/private network");
    }

    @Test
    void isAllowedHostBlocksLoopback() {
        assertThat(WebTools.isAllowedHost("localhost")).isFalse();
        assertThat(WebTools.isAllowedHost("127.0.0.1")).isFalse();
    }

    @Test
    void isAllowedHostBlocksSiteLocal() {
        assertThat(WebTools.isAllowedHost("192.168.1.1")).isFalse();
        assertThat(WebTools.isAllowedHost("10.0.0.1")).isFalse();
        assertThat(WebTools.isAllowedHost("172.16.0.1")).isFalse();
    }

    @Test
    void isAllowedHostBlocksLinkLocal() {
        assertThat(WebTools.isAllowedHost("169.254.169.254")).isFalse();
    }

    @Test
    void isAllowedHostAllowsPublicAddresses() {
        // example.com resolves to a public IP
        assertThat(WebTools.isAllowedHost("93.184.216.34")).isTrue();
    }

    @Test
    void isAllowedHostBlocksUnresolvableHosts() {
        // Completely invalid hostname should be blocked
        assertThat(WebTools.isAllowedHost("")).isFalse();
    }

    // --- Retry logic tests ---

    @Test
    void fetchRetriesOnIOException() {
        AtomicInteger attempts = new AtomicInteger(0);
        WebTools tools = toolsWithFetcher(url -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IOException("Connection reset");
            }
            return new WebTools.HttpResult(200, "<html><body>Success</body></html>", "text/html");
        });

        String result = tools.web_fetch("https://example.com");
        assertThat(result).contains("Success");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void fetchRetriesOn5xxErrors() {
        AtomicInteger attempts = new AtomicInteger(0);
        WebTools tools = toolsWithFetcher(url -> {
            if (attempts.incrementAndGet() < 3) {
                return new WebTools.HttpResult(503, "Service Unavailable", "text/html");
            }
            return new WebTools.HttpResult(200, "<html><body>Recovered</body></html>", "text/html");
        });

        String result = tools.web_fetch("https://example.com");
        assertThat(result).contains("Recovered");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void fetchReturnsErrorAfterAllRetriesExhausted() {
        AtomicInteger attempts = new AtomicInteger(0);
        WebTools tools = toolsWithFetcher(url -> {
            attempts.incrementAndGet();
            throw new IOException("Connection refused");
        });

        String result = tools.web_fetch("https://example.com");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("retries");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void searchRetriesOnIOException() {
        AtomicInteger attempts = new AtomicInteger(0);
        WebTools tools = toolsWithSearcher((q, k) -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IOException("Timeout");
            }
            return new WebTools.HttpResult(200,
                    "{\"web\":{\"results\":[{\"title\":\"Result\",\"url\":\"https://example.com\",\"description\":\"Desc\"}]}}",
                    "application/json");
        });

        String result = tools.web_search("test");
        assertThat(result).contains("**Result**");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void fetchDoesNotRetryNonIOExceptions() {
        AtomicInteger attempts = new AtomicInteger(0);
        WebTools tools = toolsWithFetcher(url -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("Bad URL");
        });

        String result = tools.web_fetch("https://example.com");
        assertThat(result).contains("\"error\"");
        assertThat(attempts.get()).isEqualTo(1);
    }

    // --- web_search tests ---

    @Test
    void searchReturnsErrorWhenApiKeyNotConfigured() {
        WebTools tools = new WebTools("", url -> new WebTools.HttpResult(200, "", ""),
                (q, k) -> new WebTools.HttpResult(200, "{}", "application/json"));

        String result = tools.web_search("test query");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("not configured");
    }

    @Test
    void searchReturnsErrorForEmptyQuery() {
        WebTools tools = toolsWithSearcher((q, k) -> new WebTools.HttpResult(200, "{}", "application/json"));

        String result = tools.web_search("");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("must not be empty");
    }

    @Test
    void searchReturnsFormattedResults() {
        String braveResponse = """
                {"web": {"results": [
                    {"title": "Spring AI Docs", "url": "https://spring.io/ai", "description": "Official docs for Spring AI"},
                    {"title": "Spring AI GitHub", "url": "https://github.com/spring-projects/spring-ai", "description": "Source code"}
                ]}}""";

        WebTools tools = toolsWithSearcher((q, k) ->
                new WebTools.HttpResult(200, braveResponse, "application/json"));

        String result = tools.web_search("Spring AI");
        assertThat(result).contains("## Search Results");
        assertThat(result).contains("**Spring AI Docs**");
        assertThat(result).contains("https://spring.io/ai");
        assertThat(result).contains("Official docs for Spring AI");
        assertThat(result).contains("**Spring AI GitHub**");
    }

    @Test
    void searchHandlesUnicodeInResults() {
        String braveResponse = """
                {"web": {"results": [
                    {"title": "What\\u2019s New in Spring", "url": "https://spring.io", "description": "Spring\\u2019s latest"}
                ]}}""";

        WebTools tools = toolsWithSearcher((q, k) ->
                new WebTools.HttpResult(200, braveResponse, "application/json"));

        String result = tools.web_search("Spring");
        assertThat(result).contains("What\u2019s New in Spring");
        assertThat(result).contains("Spring\u2019s latest");
    }

    @Test
    void searchReturnsErrorForHttpError() {
        WebTools tools = toolsWithSearcher((q, k) ->
                new WebTools.HttpResult(429, "Rate limited", "application/json"));

        String result = tools.web_search("test");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("HTTP 429");
    }

    @Test
    void searchHandlesExceptions() {
        WebTools tools = toolsWithSearcher((q, k) -> {
            throw new RuntimeException("DNS resolution failed");
        });

        String result = tools.web_search("test");
        assertThat(result).contains("\"error\"");
        assertThat(result).contains("DNS resolution failed");
    }

    @Test
    void searchPassesApiKey() {
        String[] capturedKey = new String[1];
        WebTools tools = new WebTools("my-secret-key",
                url -> new WebTools.HttpResult(200, "", ""),
                (q, k) -> {
                    capturedKey[0] = k;
                    return new WebTools.HttpResult(200, "{\"web\":{\"results\":[]}}", "application/json");
                });

        tools.web_search("test");
        assertThat(capturedKey[0]).isEqualTo("my-secret-key");
    }

    // --- stripHtml tests ---

    @Test
    void stripHtmlRemovesScriptAndStyleBlocks() {
        String html = "<html><head><style>body{color:red}</style></head>"
                + "<body><script>alert('hi')</script><p>Content</p></body></html>";
        String result = WebTools.stripHtml(html);
        assertThat(result).contains("Content");
        assertThat(result).doesNotContain("alert");
        assertThat(result).doesNotContain("color:red");
    }

    @Test
    void stripHtmlDecodesEntities() {
        String html = "<p>A &amp; B &lt; C &gt; D &quot;E&quot; F&#39;s</p>";
        String result = WebTools.stripHtml(html);
        assertThat(result).contains("A & B < C > D \"E\" F's");
    }

    @Test
    void stripHtmlCollapsesWhitespace() {
        String html = "<p>Line 1</p>\n\n\n\n\n<p>Line 2</p>";
        String result = WebTools.stripHtml(html);
        assertThat(result).doesNotContain("\n\n\n");
    }

    // --- formatSearchResults tests ---

    @Test
    void formatSearchResultsReturnsNoResultsForMissingWeb() {
        String result = WebTools.formatSearchResults("{\"query\": \"test\"}");
        assertThat(result).isEqualTo("No search results found.");
    }

    @Test
    void formatSearchResultsReturnsNoResultsForEmptyResults() {
        String result = WebTools.formatSearchResults("{\"web\": {\"results\": []}}");
        assertThat(result).isEqualTo("No search results found.");
    }

    @Test
    void formatSearchResultsHandlesInvalidJson() {
        String result = WebTools.formatSearchResults("not json at all");
        assertThat(result).isEqualTo("No search results found.");
    }

    @Test
    void formatSearchResultsStripsHtmlFromDescription() {
        String json = """
                {"web": {"results": [
                    {"title": "Test", "url": "https://test.com", "description": "Has <b>bold</b> text"}
                ]}}""";
        String result = WebTools.formatSearchResults(json);
        assertThat(result).contains("Has bold text");
        assertThat(result).doesNotContain("<b>");
    }

    // --- Annotation tests ---

    @Test
    void webFetchHasToolAnnotation() throws NoSuchMethodException {
        Method method = WebTools.class.getMethod("web_fetch", String.class);
        Tool annotation = method.getAnnotation(Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.description()).containsIgnoringCase("fetch");
    }

    @Test
    void webSearchHasToolAnnotation() throws NoSuchMethodException {
        Method method = WebTools.class.getMethod("web_search", String.class);
        Tool annotation = method.getAnnotation(Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.description()).containsIgnoringCase("search");
    }
}
