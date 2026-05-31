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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
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
        Prompt prompt = buildPrompt(question, documentId, sourceFilter, history);

        // ── 3. Store user turn (before model call — correct ordering) ─────────
        if (hasSession) conversationService.addMessage(sessionId, "user", question);

        // ── 4. Call the LLM ───────────────────────────────────────────────────
        log.debug("Calling ChatModel:\n---\n{}\n---", prompt);
        ChatResponse response = chatModel.call(prompt);
        String answer = response.getResult().getOutput().getText();
        log.info("ChatModel responded ({} chars)", answer.length());

        // ── 5. Store assistant turn ───────────────────────────────────────────
        if (hasSession) conversationService.addMessage(sessionId, "assistant", answer);

        return new AskResponse(answer);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static final String SYSTEM_PROMPT = """
            You are a personal knowledge assistant for Shubham. 
            Answer the user's question in clear, coherent language using ONLY the context provided.
            Synthesize information across chunks into a complete answer.
            Cite sources at the end like: [Source: filename.md]
            If context doesn't contain the answer, say: "Not found in knowledge base."
            Never answer from general knowledge.
            """;

    private Prompt buildPrompt(String question, String documentId, String sourceFilter,
                               List<MessageEntry> history) {
        // ── Retrieve and format context chunks ────────────────────────────────
        String chunks = searchService.findRelevantContext(question, documentId, sourceFilter);

        log.info("Context chunks: {} chars, {} chunk blocks",
                 chunks.length(), chunks.isBlank() ? 0 : countOccurrences(chunks, "[File:"));

        // ── 1. Create System Message ──────────────────────────────────────────
        SystemMessage systemMsg = new SystemMessage(SYSTEM_PROMPT);

        // ── 2. Create Messages list with System, History, and Current User ───
        List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>();
        messages.add(systemMsg);

        for (MessageEntry entry : history) {
            if ("user".equalsIgnoreCase(entry.role())) {
                messages.add(new UserMessage(entry.content()));
            } else if ("assistant".equalsIgnoreCase(entry.role())) {
                messages.add(new AssistantMessage(entry.content()));
            }
        }

        // ── 3. Create Current User Message with Context ───────────────────────
        String userContent;
        if (chunks == null || chunks.isBlank()) {
            userContent = """
                    Context from knowledge base:
                    (No relevant context found.)
                    
                    Question: %s
                    
                    Answer in clear paragraphs. Cite sources used.""".formatted(question);
        } else {
            userContent = """
                    Context from knowledge base:
                    
                    %s
                    
                    Question: %s
                    
                    Answer in clear paragraphs. Cite sources used.""".formatted(chunks, question);
        }

        messages.add(new UserMessage(userContent));

        return new Prompt(messages);
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
