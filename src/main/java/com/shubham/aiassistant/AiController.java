package com.shubham.aiassistant;

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

    private final ChatModel chatModel;
    private final DocumentService documentService;

    public AiController(ChatModel chatModel, DocumentService documentService) {
        this.chatModel = chatModel;
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String documentId = documentService.uploadDocument(file);
        return Map.of("documentId", documentId, "filename", file.getOriginalFilename());
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        String promptText = request.question();
        
        if (request.documentId() != null && !request.documentId().isBlank()) {
            String context = documentService.getDocumentContent(request.documentId());
            if (context != null && !context.isBlank()) {
                promptText = String.format(
                    "Use the following context to answer the user's question. If the answer is not in the context, use your general knowledge but mention it is not in the context.\n\nContext:\n%s\n\nQuestion: %s",
                    context,
                    request.question()
                );
            }
        }
        
        String answer = chatModel.call(promptText);
        return new AskResponse(answer);
    }
}
