package com.easyshell.server.ai.memory;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.service.EmbeddingModelFactory;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.File;

@Slf4j
@Configuration
public class MemoryVectorStoreConfig {

    @Bean
    @Lazy
    @ConditionalOnProperty(name = "ai.memory.enabled", havingValue = "true", matchIfMissing = true)
    public VectorStore memoryVectorStore(EmbeddingModelFactory embeddingModelFactory,
                                         AgenticConfigService configService) {
        try {
            EmbeddingModel embeddingModel = embeddingModelFactory.getEmbeddingModel();
            String storePath = configService.get("ai.memory.vector-store-path", "data/memory-vectors.json");
            File storeFile = new File(storePath);

            if (storeFile.getParentFile() != null) {
                storeFile.getParentFile().mkdirs();
            }

            SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();

            if (storeFile.exists()) {
                try {
                    vectorStore.load(storeFile);
                    log.info("Loaded memory vector store from: {}", storePath);
                } catch (Exception e) {
                    log.warn("Failed to load vector store from {}, starting fresh: {}", storePath, e.getMessage());
                }
            } else {
                log.info("No existing vector store file at {}, starting empty", storePath);
            }

            return vectorStore;
        } catch (Exception e) {
            log.error("Failed to initialize VectorStore: {}. Memory/SOP features will be disabled.", e.getMessage());
            throw e;
        }
    }

    @Bean
    @ConditionalOnProperty(name = "ai.memory.enabled", havingValue = "true", matchIfMissing = true)
    public VectorStoreShutdownHook vectorStoreShutdownHook(@Lazy VectorStore memoryVectorStore,
                                                            AgenticConfigService configService) {
        return new VectorStoreShutdownHook(memoryVectorStore, configService);
    }

    @Slf4j
    public static class VectorStoreShutdownHook {

        private final VectorStore vectorStore;
        private final String storePath;

        public VectorStoreShutdownHook(VectorStore vectorStore, AgenticConfigService configService) {
            this.vectorStore = vectorStore;
            this.storePath = configService.get("ai.memory.vector-store-path", "data/memory-vectors.json");
        }

        @PreDestroy
        public void save() {
            if (vectorStore instanceof SimpleVectorStore svs) {
                try {
                    File storeFile = new File(storePath);
                    if (storeFile.getParentFile() != null) {
                        storeFile.getParentFile().mkdirs();
                    }
                    svs.save(storeFile);
                    log.info("Saved memory vector store to: {}", storePath);
                } catch (Exception e) {
                    log.error("Failed to save vector store to {}: {}", storePath, e.getMessage());
                }
            }
        }
    }
}
