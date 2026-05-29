package com.shubham.aiassistant;

import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private final Map<String, String> documentCache = new ConcurrentHashMap<>();

    public String uploadDocument(MultipartFile file) throws IOException {
        String documentId = UUID.randomUUID().toString();
        
        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };

        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();
        
        String content = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));

        documentCache.put(documentId, content);
        return documentId;
    }

    public String getDocumentContent(String documentId) {
        return documentCache.get(documentId);
    }
}
