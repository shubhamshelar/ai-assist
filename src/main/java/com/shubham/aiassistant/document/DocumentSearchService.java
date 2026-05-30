package com.shubham.aiassistant.document;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Handles vector similarity search against previously ingested documents.
 */
@Service
public class DocumentSearchService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSearchService.class);

    private static final int TOP_K = 5;

    private final VectorStore vectorStore;

    public DocumentSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Returns the concatenated text of the {@code TOP_K} most relevant chunks
     * for the given document and query.  Returns an empty string when no
     * relevant chunks are found or the inputs are invalid.
     */
    public String findRelevantContext(String documentId, String query) {
        if (documentId == null || query == null || query.isBlank()) {
            log.warn("findRelevantContext called with invalid params: documentId={}", documentId);
            return "";
        }

        log.info("Vector search: documentId='{}' query='{}'", documentId, query);

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(TOP_K)
                .filterExpression("documentId == '" + documentId + "'")
                .build();

        List<Document> hits = vectorStore.similaritySearch(request);
        log.info("Found {} matching chunks", hits.size());

        if (log.isDebugEnabled()) {
            for (int i = 0; i < hits.size(); i++) {
                String snippet = hits.get(i).getText();
                log.debug("Chunk[{}]: {}", i,
                    snippet.substring(0, Math.min(snippet.length(), 100)).replace("\n", " "));
            }
        }

        return hits.stream()
                   .map(Document::getText)
                   .collect(Collectors.joining("\n\n"));
    }
}
