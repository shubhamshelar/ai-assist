package com.shubham.aiassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shubham.aiassistant.chat.ConversationService;
import com.shubham.aiassistant.document.DocumentIngestionService;
import com.shubham.aiassistant.document.DocumentSearchService;
import com.shubham.aiassistant.web.dto.UploadResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for conversation-memory behaviour in AiController.
 *
 * Scenarios:
 *  1. First request — prompt is just the raw question (no history prefix).
 *  2. Second request — prompt contains the previous exchange.
 *  3. History entries are stored in correct order after each turn.
 *  4. Document-context path still injects conversation history (regression test for the bug we fixed).
 *  5. Null/blank sessionId — no history injected, no crash.
 *  6. History is capped at MAX_MESSAGES_PER_SESSION.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiControllerIntegrationTest {

    @Autowired private MockMvc             mockMvc;
    @Autowired private ConversationService conversationService;

    @MockBean private org.springframework.ai.chat.model.ChatModel chatModel;
    @MockBean private DocumentIngestionService ingestionService;
    @MockBean private DocumentSearchService    searchService;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        conversationService.clearAll();
        Mockito.when(chatModel.call(Mockito.anyString())).thenReturn("Mock answer");
        Mockito.when(searchService.findRelevantContext(
                Mockito.anyString(), Mockito.any(), Mockito.anyString()))
               .thenReturn("");
    }

    // ── 1. First turn: prompt is just the raw question ───────────────────────
    @Test
    void firstRequest_promptContainsOnlyQuestion() throws Exception {
        ask("session-1", "What is 2+2?", null);

        assertThat(capturePrompt(1).trim()).isEqualTo("What is 2+2?");
    }

    // ── 2. Second turn: prompt contains previous exchange ────────────────────
    @Test
    void secondRequest_promptContainsPreviousExchange() throws Exception {
        String session = "session-two-turn";
        ask(session, "What is the capital of France?", null);
        ask(session, "And of Germany?", null);

        String prompt2 = capturePrompt(2);
        assertThat(prompt2).contains("user: What is the capital of France?");
        assertThat(prompt2).contains("assistant: Mock answer");
        assertThat(prompt2).contains("And of Germany?");
    }

    // ── 3. History stored correctly after each turn ──────────────────────────
    @Test
    void historyStoredCorrectly() throws Exception {
        String session = "session-history";
        ask(session, "Hello", null);

        List<ConversationService.MessageEntry> h1 = conversationService.getHistory(session);
        assertThat(h1).hasSize(2);
        assertThat(h1.get(0).role()).isEqualTo("user");
        assertThat(h1.get(0).content()).isEqualTo("Hello");
        assertThat(h1.get(1).role()).isEqualTo("assistant");
        assertThat(h1.get(1).content()).isEqualTo("Mock answer");

        ask(session, "How are you?", null);
        List<ConversationService.MessageEntry> h2 = conversationService.getHistory(session);
        assertThat(h2).hasSize(4);
        assertThat(h2.get(2).content()).isEqualTo("How are you?");
    }

    // ── 4. Document-context path includes conversation history ───────────────
    @Test
    void documentContext_stillIncludesHistory() throws Exception {
        String session = "session-doc";
        String docId   = "doc-123";
        Mockito.when(searchService.findRelevantContext(
                Mockito.anyString(), Mockito.eq(docId), Mockito.anyString()))
               .thenReturn("The tax rate is 30%.");

        ask(session, "What is my income?", null);     // seeds history
        ask(session, "What is my tax?",    docId);    // should carry history + doc context

        String prompt2 = capturePrompt(2);
        assertThat(prompt2).contains("user: What is my income?");
        assertThat(prompt2).contains("assistant: Mock answer");
        assertThat(prompt2).contains("The tax rate is 30%.");
        assertThat(prompt2).contains("What is my tax?");
    }

    // ── 5. Null sessionId — no history, no error ─────────────────────────────
    @Test
    void nullSessionId_noHistoryAndNoError() throws Exception {
        var body = Map.of("question", "Hello world");
        mockMvc.perform(post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
               .andExpect(status().isOk());

        assertThat(capturePrompt(1).trim()).isEqualTo("Hello world");
        assertThat(conversationService.sessionCount()).isZero();
    }

    // ── 6. History capped at MAX_MESSAGES_PER_SESSION ────────────────────────
    @Test
    void historyCappedAtMax() throws Exception {
        String session = "session-cap";
        int max = ConversationService.MAX_MESSAGES_PER_SESSION;

        for (int i = 0; i <= max / 2; i++) {   // one extra push past the cap
            ask(session, "Q" + i, null);
        }

        assertThat(conversationService.getHistory(session).size())
            .isLessThanOrEqualTo(max);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void ask(String sessionId, String question, String documentId) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("question", question);
        if (sessionId  != null) body.put("sessionId",  sessionId);
        if (documentId != null) body.put("documentId", documentId);

        mockMvc.perform(post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
               .andExpect(status().isOk());
    }

    /** Captures all prompts sent to chatModel and returns the one at callIndex (1-based). */
    private String capturePrompt(int callIndex) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatModel, Mockito.times(callIndex)).call(captor.capture());
        return captor.getAllValues().get(callIndex - 1);
    }
}
