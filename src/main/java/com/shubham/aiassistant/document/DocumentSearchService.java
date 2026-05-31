package com.shubham.aiassistant.document;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Performs vector similarity search across one or more sources.
 *
 * <p>Source filter values:
 * <ul>
 *   <li>{@code "pdf"}  — search only uploaded PDF chunks
 *       (filtered by {@code documentId} metadata)
 *   <li>{@code "vault"} — search only vault Markdown chunks
 *       (filtered by {@code source == 'vault'} metadata)
 *   <li>{@code "all"} (default) — search both sources and merge results
 * </ul>
 */
@Service
public class DocumentSearchService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSearchService.class);

    private static final int TOP_K = 5;

    private final VectorStore vectorStore;

    public DocumentSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns relevant context text for a user query.
     *
     * @param query        the user's question
     * @param documentId   if non-null, scope results to this uploaded PDF document
     * @param sourceFilter "all" | "pdf" | "vault"  (null treated as "all")
     */
    public String findRelevantContext(String query, String documentId, String sourceFilter) {
        if (query == null || query.isBlank()) return "";

        String filter = sourceFilter == null ? "all" : sourceFilter.trim().toLowerCase();

        // ── PDF-scoped search (when a specific document is active) ────────────
        if (documentId != null && !documentId.isBlank() && !"vault".equals(filter)) {
            return search(query, "documentId == '" + documentId + "'");
        }

        // ── Source-filtered search (no specific document) ─────────────────────
        return switch (filter) {
            case "vault" -> search(query, "source == 'vault'");
            case "pdf"   -> search(query, "source != 'vault'");
            default -> {  // "all"
                String vaultResults = search(query, "source == 'vault'");
                String pdfResults   = search(query, "source != 'vault'");
                if (!vaultResults.isBlank() && !pdfResults.isBlank()) {
                    yield vaultResults + "\n\n---\n\n" + pdfResults;
                }
                yield vaultResults.isBlank() ? pdfResults : vaultResults;
            }
        };
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String search(String query, String filterExpression) {
        log.info("Vector search: query='{}' filter='{}'", query, filterExpression);
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(TOP_K)
                    .filterExpression(filterExpression)
                    .build();

            List<Document> hits = vectorStore.similaritySearch(request);
            log.info("Found {} chunks (filter='{}')", hits.size(), filterExpression);

            if (log.isDebugEnabled()) {
                for (int i = 0; i < hits.size(); i++) {
                    String snippet = hits.get(i).getText();
                    log.debug("Chunk[{}]: {}", i,
                        snippet.substring(0, Math.min(snippet.length(), 100)).replace("\n", " "));
                }
            }

            return formatChunks(hits);
        } catch (Exception e) {
            // Some filter expressions may fail when the metadata key doesn't exist yet
            // (e.g. before any vault files are indexed). Degrade gracefully.
            log.warn("Search failed for filter='{}': {} — returning empty", filterExpression, e.getMessage());
            return "";
        }
    }

    /**
     * Formats retrieved chunks so the LLM can see and cite their source files.
     *
     * <p>Each chunk is rendered as:
     * <pre>
     * [File: /path/to/file.md]
     * {chunk text}
     * ---
     * </pre>
     *
     * Source resolution priority:
     * <ol>
     *   <li>{@code filePath} metadata (vault .md files)
     *   <li>{@code filename} metadata (uploaded PDF files)
     *   <li>Fallback label "unknown source"
     * </ol>
     */
    private static String formatChunks(List<Document> docs) {
        if (docs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Document doc : docs) {
            String source = resolveSource(doc);
            sb.append("[File: ").append(source).append("]\n");
            sb.append(doc.getText()).append("\n");
            sb.append("---\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String resolveSource(Document doc) {
        Object filePath = doc.getMetadata().get("filePath");
        if (filePath != null && !filePath.toString().isBlank()) {
            return filePath.toString();
        }
        Object filename = doc.getMetadata().get("filename");
        if (filename != null && !filename.toString().isBlank()) {
            return filename.toString();
        }
        return "unknown source";
    }
}
