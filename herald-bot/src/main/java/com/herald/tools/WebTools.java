package com.herald.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s{3,}");
    private static final Pattern SCRIPT_STYLE_PATTERN =
            Pattern.compile("<(script|style|noscript)[^>]*>[\\s\\S]*?</\\1>", Pattern.CASE_INSENSITIVE);

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

        try {
            HttpResult result = httpFetcher.fetch(url);

            if (result.statusCode() < 200 || result.statusCode() >= 400) {
                return "{\"error\": \"HTTP " + result.statusCode() + " fetching " + escapeJson(url) + "\"}";
            }

            String contentType = result.contentType() != null ? result.contentType() : "";
            if (!contentType.isEmpty() && !contentType.contains("text/") && !contentType.contains("application/json")
                    && !contentType.contains("application/xml")) {
                return "{\"error\": \"Unsupported content type: " + escapeJson(contentType) + "\"}";
            }

            String body = result.body();
            if (body == null || body.isBlank()) {
                return "{\"error\": \"Empty response from " + escapeJson(url) + "\"}";
            }

            // Strip HTML if content appears to be HTML
            if (contentType.contains("text/html") || body.trim().startsWith("<")) {
                body = stripHtml(body);
            }

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

        } catch (Exception e) {
            log.warn("Failed to fetch URL: {}", url, e);
            return "{\"error\": \"Failed to fetch URL: " + escapeJson(e.getMessage()) + "\"}";
        }
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

        try {
            HttpResult result = searchFetcher.search(query, searchApiKey);

            if (result.statusCode() < 200 || result.statusCode() >= 400) {
                return "{\"error\": \"Search API returned HTTP " + result.statusCode() + "\"}";
            }

            String body = result.body();
            if (body == null || body.isBlank()) {
                return "{\"error\": \"Empty response from search API\"}";
            }

            return formatSearchResults(body);

        } catch (Exception e) {
            log.warn("Web search failed for query: {}", query, e);
            return "{\"error\": \"Search failed: " + escapeJson(e.getMessage()) + "\"}";
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
     * Parses Brave Search API JSON response and formats as markdown.
     * Uses simple string parsing to avoid adding a JSON library dependency.
     */
    static String formatSearchResults(String jsonResponse) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Search Results\n\n");

        int resultCount = 0;
        int searchIdx = 0;

        // Find the "results" array in the web section
        int webIdx = jsonResponse.indexOf("\"web\"");
        if (webIdx == -1) {
            return "No search results found.";
        }

        int resultsIdx = jsonResponse.indexOf("\"results\"", webIdx);
        if (resultsIdx == -1) {
            return "No search results found.";
        }

        searchIdx = resultsIdx;

        // Parse each result entry by finding title, url, and description fields
        while (resultCount < 10) {
            int titleIdx = jsonResponse.indexOf("\"title\"", searchIdx);
            if (titleIdx == -1) break;

            String title = extractJsonStringValue(jsonResponse, titleIdx);
            int urlIdx = jsonResponse.indexOf("\"url\"", titleIdx);
            String url = urlIdx != -1 ? extractJsonStringValue(jsonResponse, urlIdx) : "";
            int descIdx = jsonResponse.indexOf("\"description\"", titleIdx);
            int nextTitleIdx = jsonResponse.indexOf("\"title\"", titleIdx + 10);
            String description = "";
            if (descIdx != -1 && (nextTitleIdx == -1 || descIdx < nextTitleIdx)) {
                description = extractJsonStringValue(jsonResponse, descIdx);
            }

            if (!title.isEmpty()) {
                resultCount++;
                sb.append(resultCount).append(". **").append(title).append("**\n");
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

            searchIdx = titleIdx + 10;
        }

        if (resultCount == 0) {
            return "No search results found.";
        }

        return sb.toString().strip();
    }

    /**
     * Extracts the string value from a JSON key-value pair starting at the given position.
     * Expects format: "key": "value" or "key":"value"
     */
    static String extractJsonStringValue(String json, int keyStart) {
        int colonIdx = json.indexOf(':', keyStart);
        if (colonIdx == -1) return "";

        int openQuote = json.indexOf('"', colonIdx + 1);
        if (openQuote == -1) return "";

        StringBuilder value = new StringBuilder();
        for (int i = openQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { value.append('"'); i++; }
                else if (next == '\\') { value.append('\\'); i++; }
                else if (next == 'n') { value.append('\n'); i++; }
                else if (next == 't') { value.append('\t'); i++; }
                else if (next == 'r') { value.append('\r'); i++; }
                else { value.append(c); }
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        return value.toString();
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
