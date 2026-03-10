package com.herald.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

@Component
public class WebTools {

    private static final Logger log = LoggerFactory.getLogger(WebTools.class);

    private static final int TIMEOUT_SECONDS = 10;
    private static final int MAX_CONTENT_LENGTH = 50_000;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s{3,}");
    private static final Pattern SCRIPT_STYLE_PATTERN =
            Pattern.compile("<(script|style|noscript)[^>]*>[\\s\\S]*?</\\1>", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @FunctionalInterface
    interface HttpFetcher {
        HttpResult fetch(String url) throws Exception;
    }

    @FunctionalInterface
    interface SearchFetcher {
        HttpResult search(String query, String apiKey) throws Exception;
    }

    record HttpResult(int statusCode, String body, String contentType) {}

    private final HttpFetcher httpFetcher;
    private final SearchFetcher searchFetcher;
    private final String searchApiKey;

    @Autowired
    public WebTools(@Value("${herald.web.search-api-key:}") String searchApiKey) {
        this(searchApiKey, WebTools::executeHttpFetch, WebTools::executeSearch);
    }

    WebTools(String searchApiKey, HttpFetcher httpFetcher, SearchFetcher searchFetcher) {
        this.searchApiKey = searchApiKey;
        this.httpFetcher = httpFetcher;
        this.searchFetcher = searchFetcher;
    }

    @Tool(description = "Fetch a URL and return its readable text content with HTML stripped. "
            + "Use this to read web pages, documentation, or any text content from the internet.")
    public String web_fetch(
            @ToolParam(description = "The URL to fetch (must start with http:// or https://)") String url) {
        if (url == null || url.isBlank()) {
            return "{\"error\": \"URL must not be empty\"}";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "{\"error\": \"URL must start with http:// or https://\"}";
        }

        // SSRF protection: block private/internal IP ranges
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "{\"error\": \"Invalid URL: no host\"}";
            }
            if (!isAllowedHost(host)) {
                return "{\"error\": \"Access to internal/private network addresses is not allowed\"}";
            }
        } catch (Exception e) {
            return "{\"error\": \"Invalid URL: " + escapeJson(e.getMessage()) + "\"}";
        }

        // Retry logic for transient failures
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResult result = httpFetcher.fetch(url);

                // Retry on 5xx server errors
                if (result.statusCode() >= 500 && attempt < MAX_RETRIES) {
                    log.info("Received HTTP {} from {}, retrying (attempt {}/{})",
                            result.statusCode(), url, attempt, MAX_RETRIES);
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                    continue;
                }

                if (result.statusCode() < 200 || result.statusCode() >= 400) {
                    return "{\"error\": \"HTTP " + result.statusCode() + " fetching " + escapeJson(url) + "\"}";
                }

                String contentType = result.contentType() != null ? result.contentType() : "";
                if (!contentType.isEmpty() && !contentType.contains("text/html")) {
                    return "{\"error\": \"Unsupported content type: " + escapeJson(contentType)
                            + ". Only text/html is supported.\"}";
                }

                String body = result.body();
                if (body == null || body.isBlank()) {
                    return "{\"error\": \"Empty response from " + escapeJson(url) + "\"}";
                }

                // Strip HTML
                body = stripHtml(body);

                // Truncate long content
                boolean truncated = false;
                if (body.length() > MAX_CONTENT_LENGTH) {
                    body = body.substring(0, MAX_CONTENT_LENGTH);
                    truncated = true;
                }

                if (truncated) {
                    return body + "\n\n[Content truncated at " + MAX_CONTENT_LENGTH + " characters]";
                }
                return body;

            } catch (IOException e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    log.info("IOException fetching {}, retrying (attempt {}/{}): {}",
                            url, attempt, MAX_RETRIES, e.getMessage());
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return "{\"error\": \"Interrupted during retry\"}";
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch URL: {}", url, e);
                return "{\"error\": \"Failed to fetch URL: " + escapeJson(e.getMessage()) + "\"}";
            }
        }

        log.warn("Failed to fetch URL after {} retries: {}", MAX_RETRIES, url, lastException);
        return "{\"error\": \"Failed to fetch URL after " + MAX_RETRIES + " retries: "
                + escapeJson(lastException != null ? lastException.getMessage() : "unknown error") + "\"}";
    }

    @Tool(description = "Search the web and return top results with titles, URLs, and snippets. "
            + "Use this to find information, documentation, or answers on the internet.")
    public String web_search(
            @ToolParam(description = "The search query") String query) {
        if (query == null || query.isBlank()) {
            return "{\"error\": \"Search query must not be empty\"}";
        }

        if (searchApiKey == null || searchApiKey.isBlank()) {
            return "{\"error\": \"Web search is not configured. Set HERALD_WEB_SEARCH_API_KEY environment variable "
                    + "with a Brave Search API key.\"}";
        }

        // Retry logic for transient failures
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResult result = searchFetcher.search(query, searchApiKey);

                // Retry on 5xx server errors
                if (result.statusCode() >= 500 && attempt < MAX_RETRIES) {
                    log.info("Search API returned HTTP {}, retrying (attempt {}/{})",
                            result.statusCode(), attempt, MAX_RETRIES);
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                    continue;
                }

                if (result.statusCode() < 200 || result.statusCode() >= 400) {
                    return "{\"error\": \"Search API returned HTTP " + result.statusCode() + "\"}";
                }

                String body = result.body();
                if (body == null || body.isBlank()) {
                    return "{\"error\": \"Empty response from search API\"}";
                }

                return formatSearchResults(body);

            } catch (IOException e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    log.info("IOException during search, retrying (attempt {}/{}): {}",
                            attempt, MAX_RETRIES, e.getMessage());
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return "{\"error\": \"Interrupted during retry\"}";
                    }
                }
            } catch (Exception e) {
                log.warn("Web search failed for query: {}", query, e);
                return "{\"error\": \"Search failed: " + escapeJson(e.getMessage()) + "\"}";
            }
        }

        log.warn("Web search failed after {} retries for query: {}", MAX_RETRIES, query, lastException);
        return "{\"error\": \"Search failed after " + MAX_RETRIES + " retries: "
                + escapeJson(lastException != null ? lastException.getMessage() : "unknown error") + "\"}";
    }

    /**
     * Validates that a hostname does not resolve to a private/internal IP address.
     * Blocks loopback, link-local, site-local, and multicast addresses to prevent SSRF.
     */
    static boolean isAllowedHost(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isMulticastAddress()
                    || addr.isAnyLocalAddress()) {
                return false;
            }
            // Block AWS/cloud metadata endpoint (169.254.169.254)
            byte[] bytes = addr.getAddress();
            if (bytes.length == 4 && (bytes[0] & 0xFF) == 169 && (bytes[1] & 0xFF) == 254) {
                return false; // Link-local range, covered by isLinkLocalAddress but explicit for clarity
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to resolve host for SSRF check: {}", host, e);
            return false;
        }
    }

    static String stripHtml(String html) {
        // Remove script, style, and noscript blocks
        String cleaned = SCRIPT_STYLE_PATTERN.matcher(html).replaceAll(" ");
        // Remove all HTML tags
        cleaned = HTML_TAG_PATTERN.matcher(cleaned).replaceAll(" ");
        // Decode common HTML entities
        cleaned = cleaned.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        // Collapse excessive whitespace
        cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll("\n\n");
        return cleaned.strip();
    }

    /**
     * Parses Brave Search API JSON response and formats as markdown using Jackson.
     */
    static String formatSearchResults(String jsonResponse) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonResponse);
            JsonNode webNode = root.path("web");
            if (webNode.isMissingNode()) {
                return "No search results found.";
            }
            JsonNode results = webNode.path("results");
            if (results.isMissingNode() || !results.isArray() || results.isEmpty()) {
                return "No search results found.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## Search Results\n\n");
            int count = 0;

            for (JsonNode result : results) {
                if (count >= 10) break;

                String title = result.path("title").asText("");
                String url = result.path("url").asText("");
                String description = result.path("description").asText("");

                if (title.isEmpty()) continue;

                count++;
                sb.append(count).append(". **").append(title).append("**\n");
                if (!url.isEmpty()) {
                    sb.append("   ").append(url).append("\n");
                }
                if (!description.isEmpty()) {
                    // Strip any HTML tags from description
                    String cleanDesc = HTML_TAG_PATTERN.matcher(description).replaceAll("");
                    sb.append("   ").append(cleanDesc).append("\n");
                }
                sb.append("\n");
            }

            if (count == 0) {
                return "No search results found.";
            }
            return sb.toString().strip();

        } catch (Exception e) {
            log.warn("Failed to parse search results", e);
            return "No search results found.";
        }
    }

    private static HttpResult executeHttpFetch(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("User-Agent", "Herald-Bot/1.0")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        String contentType = response.headers().firstValue("content-type").orElse("");
        return new HttpResult(response.statusCode(), response.body(), contentType);
    }

    private static HttpResult executeSearch(String query, String apiKey) throws Exception {
        String encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        String searchUrl = "https://api.search.brave.com/res/v1/web/search?q=" + encodedQuery + "&count=10";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        String contentType = response.headers().firstValue("content-type").orElse("");
        return new HttpResult(response.statusCode(), response.body(), contentType);
    }

    private static String escapeJson(String value) {
        if (value == null) return "null";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
