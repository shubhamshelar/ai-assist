package com.shubham.aiassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Scenarios covered:
 *  1. First request has NO history prefix in prompt.
 *  2. Second request contains the first exchange in its prompt.
 *  3. History is stored correctly after each turn.
 *  4. Session with documentId still includes conversation history in the prompt.
 *  5. Unknown / blank sessionId is handled gracefully (no history injected).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ConversationService conversationService;

    @MockBean private org.springframework.ai.chat.model.ChatModel chatModel;
    @MockBean private DocumentService documentService;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SESSION = "test-session-abc";

    @BeforeEach
    void setUp() {
        // Reset shared singleton state so tests don't bleed into each other
        conversationService.clearAll();
        // Deterministic LLM answer for every call
        Mockito.when(chatModel.call(Mockito.anyString())).thenReturn("Mock answer");
        // No vector-store context by default
        Mockito.when(documentService.getSimilarContext(Mockito.anyString(), Mockito.anyString()))
               .thenReturn("");
    }

    // ── 1. First turn: prompt is just the raw question ──────────────────────
    @Test
    void firstRequest_promptContainsOnlyQuestion() throws Exception {
        askAndExpect200(SESSION, "What is 2+2?", null);

        String prompt = captureLastPrompt(1);
        assertThat(prompt.trim()).isEqualTo("What is 2+2?");
    }

    // ── 2. Second turn: prompt contains previous exchange ───────────────────
    @Test
    void secondRequest_promptContainsPreviousExchange() throws Exception {
        String session = "session-two-turn";

        askAndExpect200(session, "What is the capital of France?", null);
        askAndExpect200(session, "And of Germany?", null);

        String prompt2 = captureLastPrompt(2);
        assertThat(prompt2).contains("user: What is the capital of France?");
        assertThat(prompt2).contains("assistant: Mock answer");
        assertThat(prompt2).contains("And of Germany?");
    }

    // ── 3. History stored correctly after each turn ──────────────────────────
    @Test
    void historyStoredCorrectly() throws Exception {
        String session = "session-history-check";

        askAndExpect200(session, "Hello", null);
        List<ConversationService.MessageEntry> h1 = conversationService.getHistory(session);
        assertThat(h1).hasSize(2);
        assertThat(h1.get(0).role()).isEqualTo("user");
        assertThat(h1.get(0).content()).isEqualTo("Hello");
        assertThat(h1.get(1).role()).isEqualTo("assistant");
        assertThat(h1.get(1).content()).isEqualTo("Mock answer");

        askAndExpect200(session, "How are you?", null);
        List<ConversationService.MessageEntry> h2 = conversationService.getHistory(session);
        assertThat(h2).hasSize(4);
        assertThat(h2.get(2).role()).isEqualTo("user");
        assertThat(h2.get(2).content()).isEqualTo("How are you?");
    }

    // ── 4. Document-context path still includes conversation history ─────────
    @Test
    void documentContext_stillIncludesHistory() throws Exception {
        String session = "session-doc-memory";
        String docId   = "doc-123";

        // Mock a non-empty context for the second call
        Mockito.when(documentService.getSimilarContext(Mockito.eq(docId), Mockito.anyString()))
               .thenReturn("The tax rate is 30%.");

        // First turn (plain, no doc) — seeds history
        askAndExpect200(session, "What is my income?", null);

        // Second turn with a document — must still carry history prefix
        askAndExpect200(session, "What is my tax?", docId);

        String prompt2 = captureLastPrompt(2);
        // History prefix must be present
        assertThat(prompt2).contains("user: What is my income?");
        assertThat(prompt2).contains("assistant: Mock answer");
        // Document context must also be present
        assertThat(prompt2).contains("The tax rate is 30%.");
        // Current question must also be present
        assertThat(prompt2).contains("What is my tax?");
    }

    // ── 5. Missing / blank sessionId: no history, no crash ──────────────────
    @Test
    void blankSessionId_noHistoryAndNoError() throws Exception {
        // null sessionId
        var body = Map.of("question", "Hello world");
        mockMvc.perform(post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
               .andExpect(status().isOk());

        String prompt = captureLastPrompt(1);
        assertThat(prompt.trim()).isEqualTo("Hello world");
        // Verify nothing was stored (session count shouldn't increase for null)
        assertThat(conversationService.sessionCount()).isZero();
    }

    // ── 6. History capped at MAX_MESSAGES_PER_SESSION ───────────────────────
    @Test
    void historyIsCappedAtMaxMessages() throws Exception {
        String session = "session-cap-test";
        int max = ConversationService.MAX_MESSAGES_PER_SESSION;

        // Send enough messages to exceed the cap (each ask stores 2 entries)
        for (int i = 0; i < (max / 2) + 2; i++) {
            askAndExpect200(session, "Question " + i, null);
        }

        List<ConversationService.MessageEntry> history = conversationService.getHistory(session);
        assertThat(history.size()).isLessThanOrEqualTo(max);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void askAndExpect200(String sessionId, String question, String documentId)
            throws Exception {
        var body = new java.util.HashMap<String, String>();
        body.put("question", question);
        if (sessionId  != null) body.put("sessionId",  sessionId);
        if (documentId != null) body.put("documentId", documentId);

        mockMvc.perform(post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
               .andExpect(status().isOk());
    }

    /** Captures all prompts sent to chatModel and returns the one at {@code callIndex} (1-based). */
    private String captureLastPrompt(int callIndex) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatModel, Mockito.times(callIndex)).call(captor.capture());
        return captor.getAllValues().get(callIndex - 1);
    }
}
