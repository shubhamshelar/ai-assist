package com.shubham.aiassistant;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final ChatModel chatModel;
    private final DocumentService documentService;
    private final ConversationService conversationService;

    public AiController(ChatModel chatModel,
                        DocumentService documentService,
                        ConversationService conversationService) {
        this.chatModel = chatModel;
        this.documentService = documentService;
        this.conversationService = conversationService;
    }

    // -----------------------------------------------------------------------
    // POST /upload
    // -----------------------------------------------------------------------
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        log.info("Received POST /upload for file: {}", filename);
        UploadResult result = documentService.uploadDocument(file);
        log.info("Completed POST /upload. documentId={}, duplicate={}", result.documentId(), result.duplicate());
        return Map.of(
            "documentId", result.documentId(),
            "filename",   filename != null ? filename : "",
            "duplicate",  result.duplicate()
        );
    }

    // -----------------------------------------------------------------------
    // POST /ask
    // -----------------------------------------------------------------------
    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        String sessionId   = request.sessionId();
        String question    = request.question();
        String documentId  = request.documentId();

        log.info("POST /ask — question='{}', documentId='{}', sessionId='{}'",
                 question, documentId, sessionId);

        // ── 1. Resolve conversation history (snapshot before we mutate) ───────
        boolean hasSession = sessionId != null && !sessionId.isBlank();
        List<ConversationService.MessageEntry> history =
                hasSession ? conversationService.getHistory(sessionId) : List.of();

        // ── 2. Build the history prefix (shared for both prompt paths) ────────
        String historyPrefix = buildHistoryPrefix(history);

        // ── 3. Build the final prompt ─────────────────────────────────────────
        String promptText;
        if (documentId != null && !documentId.isBlank()) {
            log.debug("Fetching vector-store context for documentId={}", documentId);
            String context = documentService.getSimilarContext(documentId, question);
            if (context != null && !context.isBlank()) {
                log.info("Context retrieved ({} chars). Injecting into prompt.", context.length());
                // History + document context + current question — nothing is lost
                promptText = String.format(
                    "%sUse the following document context to answer the user's question. " +
                    "If the answer is not in the context, use your general knowledge but say so.\n\n" +
                    "Document context:\n%s\n\nQuestion: %s",
                    historyPrefix, context, question);
            } else {
                log.warn("No context found for documentId={}. Proceeding without context.", documentId);
                promptText = historyPrefix + question;
            }
        } else {
            log.info("No documentId provided. Using raw question with history.");
            promptText = historyPrefix + question;
        }

        // ── 4. Store the user turn BEFORE calling the model ──────────────────
        if (hasSession) {
            conversationService.addMessage(sessionId, "user", question);
        }

        // ── 5. Call the LLM ───────────────────────────────────────────────────
        log.debug("Calling ChatModel — prompt:\n---START---\n{}\n---END---", promptText);
        String answer = chatModel.call(promptText);
        log.info("ChatModel responded ({} chars).", answer.length());
        log.debug("LLM response: {}", answer);

        // ── 6. Store the assistant turn ───────────────────────────────────────
        if (hasSession) {
            conversationService.addMessage(sessionId, "assistant", answer);
        }

        return new AskResponse(answer);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Converts the message history list into a prefix string ready to prepend
     * to a prompt.  Returns an empty string when there is no history.
     */
    private String buildHistoryPrefix(List<ConversationService.MessageEntry> history) {
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Conversation so far:\n");
        for (ConversationService.MessageEntry entry : history) {
            sb.append(entry.role()).append(": ").append(entry.content()).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }
}
