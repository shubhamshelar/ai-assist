package com.shubham.aiassistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final ChatModel chatModel;
    private final DocumentService documentService;

    public AiController(ChatModel chatModel, DocumentService documentService) {
        this.chatModel = chatModel;
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        log.info("Received POST /upload for file: {}", filename);
        UploadResult result = documentService.uploadDocument(file);
        log.info("Completed POST /upload. Result - documentId: {}, duplicate: {}", result.documentId(), result.duplicate());
        return Map.of(
            "documentId", result.documentId(),
            "filename", file.getOriginalFilename() != null ? file.getOriginalFilename() : "",
            "duplicate", result.duplicate()
        );
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        log.info("Received POST /ask. Question: '{}', Document ID: '{}'", request.question(), request.documentId());
        String promptText = request.question();
        
        if (request.documentId() != null && !request.documentId().isBlank()) {
            log.debug("Retrieving context from database/vector store for document ID: {}", request.documentId());
            String context = documentService.getSimilarContext(request.documentId(), request.question());
            if (context != null && !context.isBlank()) {
                log.info("Context retrieved successfully (length: {} chars). Injecting into LLM prompt.", context.length());
                promptText = String.format(
                    "Use the following context to answer the user's question. If the answer is not in the context, use your general knowledge but mention it is not in the context.\n\nContext:\n%s\n\nQuestion: %s",
                    context,
                    request.question()
                );
            } else {
                log.warn("No context retrieved for document ID: {}. Proceeding without context.", request.documentId());
            }
        } else {
            log.info("No document ID provided. Proceeding with raw question.");
        }
        
        log.debug("Calling ChatModel (Groq) with prompt: \n---PROMPT START---\n{}\n---PROMPT END---", promptText);
        String answer = chatModel.call(promptText);
        log.info("ChatModel returned response successfully (length: {} chars)", answer.length());
        log.debug("LLM Response: {}", answer);
        return new AskResponse(answer);
    }
}
