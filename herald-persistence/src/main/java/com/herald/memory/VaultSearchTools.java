package com.herald.memory;

import com.herald.config.HeraldConfig;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnBean(SimpleVectorStore.class)
public class VaultSearchTools {

    private final SimpleVectorStore vectorStore;
    private final VaultIndexer vaultIndexer;
    private final int defaultTopK;

    public VaultSearchTools(SimpleVectorStore vectorStore, VaultIndexer vaultIndexer, HeraldConfig config) {
        this.vectorStore = vectorStore;
        this.vaultIndexer = vaultIndexer;
        this.defaultTopK = config.vaultToolDefaultTopK();
    }

    @Tool(description = "Search the Obsidian knowledge base using semantic similarity. Returns relevant excerpts from archived chat sessions, research notes, and other vault content. Use this for deeper searches beyond the auto-injected vault context.")
    public String vault_search(
            @ToolParam(description = "Natural language search query") String query,
            @ToolParam(description = "Number of results to return (default 5)", required = false) Integer topK) {

        int k = topK != null && topK > 0 ? topK : defaultTopK;

        List<Document> results;
        try {
            results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(k)
                            .similarityThreshold(0.5)
                            .build());
        } catch (Exception e) {
            return "Vault search failed (embedding model may be unavailable): " + e.getMessage();
        }

        if (results == null || results.isEmpty()) {
            return "No results found for query: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" result(s):\n\n");

        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            String source = (String) doc.getMetadata().getOrDefault("source_path", "unknown");
            String folder = (String) doc.getMetadata().getOrDefault("folder", "");
            String text = doc.getText();
            if (text.length() > 200) {
                text = text.substring(0, 200) + "...";
            }
            sb.append(i + 1).append(". **").append(source).append("**");
            if (!folder.isEmpty()) {
                sb.append(" (").append(folder).append(")");
            }
            sb.append("\n   ").append(text.replace("\n", "\n   ")).append("\n\n");
        }
        return sb.toString();
    }

    @Tool(description = "Re-scan and re-index the Obsidian vault. Use if you suspect the search index is stale or if files have been added/modified outside of Herald.")
    public String vault_reindex() {
        return vaultIndexer.reindex();
    }
}
