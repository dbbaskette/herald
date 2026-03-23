package com.herald.agent;

import com.herald.config.HeraldConfig;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Advisor that auto-injects top-k relevant vault excerpts into the system prompt.
 * Not a @Component — instantiated manually in HeraldAgentConfig like other advisors.
 */
class VaultSearchAdvisor implements CallAdvisor {

    private static final ThreadLocal<Boolean> INJECTED = ThreadLocal.withInitial(() -> false);

    private final VectorStore vectorStore;
    private final int topK;
    private final double minSimilarity;

    VaultSearchAdvisor(VectorStore vectorStore, HeraldConfig config) {
        this.vectorStore = vectorStore;
        this.topK = config.vaultAutoTopK();
        this.minSimilarity = config.vaultMinSimilarity();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (INJECTED.get()) {
            return chain.nextCall(request);
        }
        INJECTED.set(true);
        try {
            String userText = extractUserText(request);
            if (userText == null || userText.isBlank()) {
                return chain.nextCall(request);
            }

            List<Document> results;
            try {
                results = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(userText)
                                .topK(topK)
                                .similarityThreshold(minSimilarity)
                                .build());
            } catch (Exception e) {
                // LM Studio may be down — degrade gracefully
                return chain.nextCall(request);
            }

            if (results == null || results.isEmpty()) {
                return chain.nextCall(request);
            }

            StringBuilder vaultContext = new StringBuilder("\n\n<vault-context>\n");
            vaultContext.append("The following excerpts from your knowledge base may be relevant:\n\n");

            for (Document doc : results) {
                String source = (String) doc.getMetadata().getOrDefault("source_path", "unknown");
                String text = doc.getText();
                if (text.length() > 300) {
                    text = text.substring(0, 300) + "...";
                }
                vaultContext.append("**Source:** ").append(source).append("\n");
                vaultContext.append("> ").append(text.replace("\n", "\n> ")).append("\n\n");
            }
            vaultContext.append("</vault-context>");

            request = request.mutate()
                    .prompt(request.prompt().augmentSystemMessage(
                            existing -> new SystemMessage(existing.getText() + vaultContext)))
                    .build();

            return chain.nextCall(request);
        } finally {
            INJECTED.remove();
        }
    }

    private String extractUserText(ChatClientRequest request) {
        var messages = request.prompt().getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) {
                return um.getText();
            }
        }
        return null;
    }

    @Override
    public String getName() {
        return "VaultSearchAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 125;
    }
}
