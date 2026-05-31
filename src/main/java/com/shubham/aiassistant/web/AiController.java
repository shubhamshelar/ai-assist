package com.shubham.aiassistant.web;

import com.shubham.aiassistant.chat.ConversationService;
import com.shubham.aiassistant.chat.ConversationService.MessageEntry;
import com.shubham.aiassistant.document.DocumentIngestionService;
import com.shubham.aiassistant.document.DocumentSearchService;
import com.shubham.aiassistant.web.dto.AskRequest;
import com.shubham.aiassistant.web.dto.AskResponse;
import com.shubham.aiassistant.web.dto.UploadResponse;

import java.io.IOException;
import java.util.List;

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

    private final ChatModel                chatModel;
    private final DocumentIngestionService ingestionService;
    private final DocumentSearchService    searchService;
    private final ConversationService      conversationService;

    public AiController(ChatModel chatModel,
                        DocumentIngestionService ingestionService,
                        DocumentSearchService searchService,
                        ConversationService conversationService) {
        this.chatModel           = chatModel;
        this.ingestionService    = ingestionService;
        this.searchService       = searchService;
        this.conversationService = conversationService;
    }

    // ── POST /upload ──────────────────────────────────────────────────────────

    @PostMapping("/upload")
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        log.info("POST /upload — file='{}'", file.getOriginalFilename());
        UploadResponse result = ingestionService.upload(file);
        log.info("Upload complete — documentId={} duplicate={}", result.documentId(), result.duplicate());
        return result;
    }

    // ── POST /ask ─────────────────────────────────────────────────────────────

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        String sessionId     = request.sessionId();
        String question      = request.question();
        String documentId    = request.documentId();
        String sourceFilter  = request.sourceFilter() != null ? request.sourceFilter() : "all";

        log.info("POST /ask — question='{}' documentId='{}' sessionId='{}' sourceFilter='{}'",
                 question, documentId, sessionId, sourceFilter);

        boolean hasSession = sessionId != null && !sessionId.isBlank();

        // ── 1. Snapshot history before mutating ───────────────────────────────
        List<MessageEntry> history =
                hasSession ? conversationService.getHistory(sessionId) : List.of();

        // ── 2. Build prompt ───────────────────────────────────────────────────
        String prompt = buildPrompt(question, documentId, sourceFilter, history);

        // ── 3. Store user turn (before model call — correct ordering) ─────────
        if (hasSession) conversationService.addMessage(sessionId, "user", question);

        // ── 4. Call the LLM ───────────────────────────────────────────────────
        log.debug("Calling ChatModel:\n---\n{}\n---", prompt);
        String answer = chatModel.call(prompt);
        log.info("ChatModel responded ({} chars)", answer.length());

        // ── 5. Store assistant turn ───────────────────────────────────────────
        if (hasSession) conversationService.addMessage(sessionId, "assistant", answer);

        return new AskResponse(answer);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String buildPrompt(String question, String documentId, String sourceFilter,
                               List<MessageEntry> history) {
        String historyPrefix = buildHistoryPrefix(history);

        String context = searchService.findRelevantContext(question, documentId, sourceFilter);
        if (context != null && !context.isBlank()) {
            log.info("Injecting context ({} chars) into prompt", context.length());
            return historyPrefix
                 + "Use the following context to answer the user's question. "
                 + "If the answer is not in the context, use your general knowledge but say so.\n\n"
                 + "Context:\n" + context + "\n\nQuestion: " + question;
        }

        return historyPrefix + question;
    }

    private static String buildHistoryPrefix(List<MessageEntry> history) {
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Conversation so far:\n");
        for (MessageEntry entry : history) {
            sb.append(entry.role()).append(": ").append(entry.content()).append("\n");
        }
        return sb.append("\n").toString();
    }
}
