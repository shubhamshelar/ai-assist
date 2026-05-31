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

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are a personal knowledge assistant. You answer questions strictly based on the context provided below.

            Rules:
            1. ONLY use information from the provided context chunks. Never use your general training knowledge.
            2. For every answer, cite the exact source file path like this: [Source: /path/to/file.md]
            3. If the answer is not found in the context, say exactly: "I could not find this in your knowledge base."
            4. Never mix information from different source files without clearly labeling which file each part comes from.
            5. When asked for exact lines, quote the relevant chunk text verbatim and include the file path.
            6. Do not hallucinate or infer beyond what the context says.

            Context chunks retrieved:
            {chunks}

            Each chunk above includes a [File: path] header. Always reference it when answering.
            """;

    private String buildPrompt(String question, String documentId, String sourceFilter,
                               List<MessageEntry> history) {
        // ── Retrieve and format context chunks ────────────────────────────────
        String chunks = searchService.findRelevantContext(question, documentId, sourceFilter);

        // ── Build the system prompt ───────────────────────────────────────────
        // If no chunks were found, inject a placeholder that triggers rule #3.
        String filledSystemPrompt = SYSTEM_PROMPT_TEMPLATE.replace(
            "{chunks}",
            chunks.isBlank() ? "(No relevant context found.)" : chunks
        );

        log.info("Context chunks: {} chars, {} chunk blocks",
                 chunks.length(), chunks.isBlank() ? 0 : countOccurrences(chunks, "[File:"));

        // ── Prepend conversation history ──────────────────────────────────────
        String historySection = buildHistoryPrefix(history);

        return filledSystemPrompt + historySection + "Question: " + question;
    }

    private static String buildHistoryPrefix(List<MessageEntry> history) {
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nConversation so far:\n");
        for (MessageEntry entry : history) {
            sb.append(entry.role()).append(": ").append(entry.content()).append("\n");
        }
        return sb.append("\n").toString();
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) != -1) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
