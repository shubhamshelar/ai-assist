package com.shubham.aiassistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final Map<String, String> activeUploads = new ConcurrentHashMap<>();

    public DocumentService(VectorStore vectorStore, DocumentRepository documentRepository) {
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
    }

    public UploadResult uploadDocument(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        long size = file.getSize();
        log.info("Starting upload for file: '{}', size: {} bytes", filename, size);
        
        byte[] fileBytes = file.getBytes();
        String sha256 = calculateSha256(fileBytes);
        log.debug("Calculated SHA-256 hash for '{}': {}", filename, sha256);

        log.debug("Checking database for duplicate document with SHA-256: {}", sha256);
        Optional<DocumentEntity> existingDoc = documentRepository.findBySha256Hash(sha256);
        if (existingDoc.isPresent()) {
            String existingId = existingDoc.get().getId().toString();
            log.info("Duplicate document detected for '{}'. Existing document ID: {}", filename, existingId);
            return new UploadResult(existingId, true);
        }

        UUID documentId = UUID.randomUUID();
        log.info("Generated new document ID: {}", documentId);

        String activeId = activeUploads.putIfAbsent(sha256, documentId.toString());
        if (activeId != null) {
            log.info("File '{}' with hash {} is already being processed concurrently. Mapping to existing upload ID: {}", filename, sha256, activeId);
            return new UploadResult(activeId, true);
        }

        try {
            ByteArrayResource resource = new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            };

            log.debug("Reading document text using TikaDocumentReader");
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> documents = reader.get();
            
            String content = documents.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n"));
            log.debug("Extracted total character length: {}", content.length());

            // Split text into chunks of ~500 characters with 50 character overlap
            log.debug("Splitting text into chunks (size: 500, overlap: 50)");
            List<String> chunks = splitIntoChunks(content, 500, 50);
            log.info("Generated {} chunks for file '{}'", chunks.size(), filename);

            // Convert chunks to Document objects with documentId, filename, chunkIndex metadata
            List<Document> chunkDocuments = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Document chunkDoc = new Document(
                    chunks.get(i),
                    Map.of(
                        "documentId", documentId.toString(),
                        "filename", file.getOriginalFilename(),
                        "chunkIndex", i
                    )
                );
                chunkDocuments.add(chunkDoc);
            }

            log.info("Adding {} chunks to PgVectorStore in rate-limited batches for document ID: {}", chunkDocuments.size(), documentId);
            
            int batchSize = 20;
            for (int i = 0; i < chunkDocuments.size(); i += batchSize) {
                List<Document> batch = chunkDocuments.subList(i, Math.min(i + batchSize, chunkDocuments.size()));
                log.info("Adding batch of {} chunks to PgVectorStore (progress: {}/{})", batch.size(), i + batch.size(), chunkDocuments.size());
                vectorStore.add(batch);
                
                if (i + batchSize < chunkDocuments.size()) {
                    try {
                        log.info("Sleeping for 3 seconds to avoid rate limits on the next batch...");
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Embedding generation interrupted", e);
                    }
                }
            }
            log.debug("Successfully added all chunks to PgVectorStore");

            // Save to Database
            log.debug("Saving document metadata to SQL database");
            DocumentEntity entity = new DocumentEntity(
                documentId,
                file.getOriginalFilename(),
                sha256,
                LocalDateTime.now()
            );
            documentRepository.save(entity);
            log.info("Document metadata successfully saved to database for '{}'", filename);

            return new UploadResult(documentId.toString(), false);
        } finally {
            activeUploads.remove(sha256);
        }
    }

    private List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int length = text.length();
        int step = chunkSize - overlap;
        if (step <= 0) {
            step = 1;
        }
        for (int i = 0; i < length; i += step) {
            int end = Math.min(length, i + chunkSize);
            chunks.add(text.substring(i, end));
            if (end == length) {
                break;
            }
        }
        return chunks;
    }

    public String getSimilarContext(String documentId, String question) {
        if (documentId == null || question == null || question.isBlank()) {
            log.warn("Invalid parameters passed to getSimilarContext: documentId={}, question={}", documentId, question);
            return "";
        }
        log.info("Searching similarity in vector store for documentId: '{}', question: '{}'", documentId, question);
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(5)
                .filterExpression("documentId == '" + documentId + "'")
                .build();
        
        log.debug("Executing similarity search with query: '{}' and topK: {}", question, 5);
        List<Document> results = vectorStore.similaritySearch(searchRequest);
        log.info("Found {} matching chunks in vector store", results.size());
        
        if (log.isDebugEnabled()) {
            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                log.debug("Chunk {} content snippet: {}", i, doc.getText().substring(0, Math.min(doc.getText().length(), 100)).replace("\n", " "));
            }
        }
        
        return results.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));
    }

    private String calculateSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
