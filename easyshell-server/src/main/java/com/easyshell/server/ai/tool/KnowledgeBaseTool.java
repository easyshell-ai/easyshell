package com.easyshell.server.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class KnowledgeBaseTool {

    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public KnowledgeBaseTool(ObjectProvider<VectorStore> vectorStoreProvider) {
        this.vectorStoreProvider = vectorStoreProvider;
    }

    @Tool(description = "从知识库中搜索相关文档。适用于查找内部文档、操作手册、FAQ 等。需要系统已配置 VectorStore。")
    public String searchKnowledge(
            @ToolParam(description = "搜索查询") String query,
            @ToolParam(description = "返回结果数量，默认 5，最大 10") int topK) {
        try {
            VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore == null) {
                return "知识库功能不可用：未配置 VectorStore（需要配置 AI embedding 模型）";
            }

            if (query == null || query.isBlank()) {
                return "搜索查询不能为空";
            }

            int limit = Math.max(1, Math.min(topK <= 0 ? 5 : topK, 10));

            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(limit).build());

            if (results == null || results.isEmpty()) {
                return "知识库中未找到与 \"" + query + "\" 相关的文档";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("知识库搜索结果（").append(results.size()).append(" 条）：\n\n");

            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                sb.append(i + 1).append(". ");

                // Try to get document title/source from metadata
                if (doc.getMetadata() != null) {
                    String source = doc.getMetadata().getOrDefault("source", "").toString();
                    if (!source.isEmpty()) {
                        sb.append("**来源**: ").append(source).append("\n   ");
                    }
                }

                String content = doc.getText();
                if (content != null && content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                sb.append(content != null ? content : "(无内容)").append("\n\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Knowledge base search failed: {}", e.getMessage());
            return "知识库搜索失败：" + e.getMessage();
        }
    }
}
