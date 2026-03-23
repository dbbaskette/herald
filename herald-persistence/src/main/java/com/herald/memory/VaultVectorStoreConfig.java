package com.herald.memory;

import com.herald.config.HeraldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Path;

@Configuration
public class VaultVectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VaultVectorStoreConfig.class);

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel, HeraldConfig config) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        Path storePath = resolveTildePath(config.vaultVectorStorePath());
        File storeFile = storePath.toFile();
        if (storeFile.exists()) {
            log.info("Loading vector store from {}", storePath);
            store.load(storeFile);
        } else {
            log.info("Vector store file not found at {} — starting empty", storePath);
        }

        return store;
    }

    @Bean
    @ConditionalOnBean(SimpleVectorStore.class)
    public VaultIndexer vaultIndexer(SimpleVectorStore vectorStore, HeraldConfig config) {
        return new VaultIndexer(vectorStore, config);
    }

    @Bean
    @ConditionalOnBean(SimpleVectorStore.class)
    public VaultSearchTools vaultSearchTools(SimpleVectorStore vectorStore, VaultIndexer vaultIndexer, HeraldConfig config) {
        return new VaultSearchTools(vectorStore, vaultIndexer, config);
    }

    static Path resolveTildePath(String path) {
        if (path.startsWith("~/") || path.equals("~")) {
            return Path.of(System.getProperty("user.home")).resolve(path.substring(2));
        }
        return Path.of(path);
    }
}
