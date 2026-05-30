package com.shubham.aiassistant.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * In-memory, per-session conversation history.
 *
 * <p>Design:
 * <ul>
 *   <li>Sessions are stored in an LRU-evicting {@link LinkedHashMap} capped at
 *       {@value #MAX_SESSIONS} entries — old sessions are dropped automatically
 *       on access-order so the most recently used sessions survive.</li>
 *   <li>Per-session lists are synchronised independently, so concurrent
 *       requests on different sessions don't contend.</li>
 *   <li>History is capped at {@value #MAX_MESSAGES_PER_SESSION} entries (user
 *       + assistant turns combined); oldest entries are trimmed first.</li>
 * </ul>
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** Maximum number of concurrent sessions kept in memory. */
    public static final int MAX_SESSIONS             = 1_000;
    /** Maximum messages retained per session (user + assistant turns combined). */
    public static final int MAX_MESSAGES_PER_SESSION = 20;   // 10 exchanges

    /** A single message turn in a conversation. */
    public record MessageEntry(String role, String content) {}

    private final Map<String, List<MessageEntry>> sessions =
            Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, /* access-order= */ true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, List<MessageEntry>> eldest) {
                        boolean evict = size() > MAX_SESSIONS;
                        if (evict) {
                            log.info("Session evicted (LRU): '{}'", eldest.getKey());
                        }
                        return evict;
                    }
                });

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns an immutable snapshot of the history for {@code sessionId}.
     * Returns an empty list if the session is unknown.
     */
    public List<MessageEntry> getHistory(String sessionId) {
        List<MessageEntry> history = sessions.get(sessionId);
        if (history == null) return List.of();
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /**
     * Appends a message and trims the oldest entries when the per-session cap
     * is exceeded.
     */
    public void addMessage(String sessionId, String role, String content) {
        List<MessageEntry> history =
                sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
        synchronized (history) {
            history.add(new MessageEntry(role, content));
            if (history.size() > MAX_MESSAGES_PER_SESSION) {
                history.subList(0, history.size() - MAX_MESSAGES_PER_SESSION).clear();
            }
        }
    }

    /** Returns the total number of active sessions. */
    public int sessionCount() {
        return sessions.size();
    }

    /** Removes all sessions. Intended for use in tests only. */
    public void clearAll() {
        sessions.clear();
    }
}
