package com.shubham.aiassistant.document;

import com.shubham.aiassistant.util.HashUtils;
import com.shubham.aiassistant.web.dto.UploadResponse;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Handles PDF ingestion: deduplication, text extraction, chunking, and
 * storage in both the vector store and the SQL metadata table.
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    /** Chunk size in characters. */
    private static final int CHUNK_SIZE    = 500;
    /** Overlap between consecutive chunks in characters. */
    private static final int CHUNK_OVERLAP = 50;

    private final VectorStore          vectorStore;
    private final DocumentRepository   documentRepository;
    /** Guards against concurrent uploads of the same file (sha256 → documentId). */
    private final Map<String, String>  activeUploads = new ConcurrentHashMap<>();

    public DocumentIngestionService(VectorStore vectorStore, DocumentRepository documentRepository) {
        this.vectorStore        = vectorStore;
        this.documentRepository = documentRepository;
    }

    /**
     * Uploads and indexes a PDF file.
     * Returns immediately with the existing documentId if the file is a duplicate.
     */
    public UploadResponse upload(MultipartFile file) throws IOException {
        String filename  = file.getOriginalFilename();
        byte[] bytes     = file.getBytes();
        String sha256    = HashUtils.sha256Hex(bytes);

        log.info("Upload started: file='{}' size={} sha256={}", filename, file.getSize(), sha256);

        // ── Duplicate check (DB) ─────────────────────────────────────────────
        Optional<DocumentEntity> existing = documentRepository.findBySha256Hash(sha256);
        if (existing.isPresent()) {
            String id = existing.get().getId().toString();
            log.info("Duplicate detected for '{}' → existing documentId={}", filename, id);
            return new UploadResponse(id, filename, true);
        }

        // ── In-flight dedup (concurrent uploads of the same file) ────────────
        UUID documentId  = UUID.randomUUID();
        String raceId    = activeUploads.putIfAbsent(sha256, documentId.toString());
        if (raceId != null) {
            log.info("Concurrent upload detected for sha256={} → reusing id={}", sha256, raceId);
            return new UploadResponse(raceId, filename, true);
        }

        try {
            // ── Extract text ─────────────────────────────────────────────────
            ByteArrayResource resource = namedResource(bytes, filename);
            List<Document> pages = new TikaDocumentReader(resource).get();
            String text = pages.stream().map(Document::getText).collect(Collectors.joining("\n"));
            log.debug("Extracted {} chars from '{}'", text.length(), filename);

            // ── Chunk ─────────────────────────────────────────────────────────
            List<String> chunks = chunk(text, CHUNK_SIZE, CHUNK_OVERLAP);
            log.info("{} chunks generated for '{}'", chunks.size(), filename);

            List<Document> chunkDocs = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                chunkDocs.add(new Document(
                    chunks.get(i),
                    Map.of("documentId", documentId.toString(),
                           "filename",   filename != null ? filename : "",
                           "chunkIndex", i)));
            }

            // ── Store in vector store ─────────────────────────────────────────
            vectorStore.add(chunkDocs);
            log.info("Stored {} chunks in vector store for documentId={}", chunkDocs.size(), documentId);

            // ── Persist metadata ──────────────────────────────────────────────
            documentRepository.save(
                new DocumentEntity(documentId, filename, sha256, LocalDateTime.now()));
            log.info("Metadata saved for '{}' documentId={}", filename, documentId);

            return new UploadResponse(documentId.toString(), filename, false);
        } finally {
            activeUploads.remove(sha256);
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static ByteArrayResource namedResource(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        };
    }

    /**
     * Splits {@code text} into overlapping chunks of at most {@code size} chars,
     * stepping {@code size - overlap} chars between each chunk.
     */
    static List<String> chunk(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;
        int step = Math.max(1, size - overlap);
        for (int i = 0; i < text.length(); i += step) {
            result.add(text.substring(i, Math.min(text.length(), i + size)));
            if (i + size >= text.length()) break;
        }
        return result;
    }
}
