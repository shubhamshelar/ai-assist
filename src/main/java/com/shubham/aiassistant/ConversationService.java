package com.shubham.aiassistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * In-memory conversation history store.
 *
 * Design decisions:
 * - Per-session history is stored in a synchronised ArrayList so concurrent
 *   requests on the same session don't corrupt the list.
 * - Sessions are kept in an LRU-evicting LinkedHashMap capped at MAX_SESSIONS
 *   so a long-running server doesn't leak memory indefinitely.
 * - Only the last MAX_MESSAGES_PER_SESSION messages are retained per session
 *   (trimmed from the oldest end).
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** Maximum number of concurrent sessions kept in memory. */
    static final int MAX_SESSIONS = 1_000;

    /** Maximum messages retained per session (user + assistant turns combined). */
    static final int MAX_MESSAGES_PER_SESSION = 20; // 10 exchanges

    /** Record representing a single message in the conversation. */
    public record MessageEntry(String role, String content) {}

    /**
     * LRU-evicting map: when it exceeds MAX_SESSIONS the oldest-accessed entry
     * is removed automatically.
     */
    private final Map<String, List<MessageEntry>> sessions =
            Collections.synchronizedMap(
                new LinkedHashMap<>(MAX_SESSIONS, 0.75f, true /* access-order */) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, List<MessageEntry>> eldest) {
                        boolean evict = size() > MAX_SESSIONS;
                        if (evict) {
                            log.info("ConversationService: evicting oldest session '{}' (total sessions: {})",
                                     eldest.getKey(), size());
                        }
                        return evict;
                    }
                });

    /**
     * Returns an unmodifiable snapshot of the history for a session.
     * Returns an empty list if the session is unknown.
     */
    public List<MessageEntry> getHistory(String sessionId) {
        List<MessageEntry> history = sessions.get(sessionId);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /**
     * Appends a message to the session history, trimming the oldest entries when
     * the cap is exceeded. Thread-safe per session.
     */
    public void addMessage(String sessionId, String role, String content) {
        List<MessageEntry> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
        synchronized (history) {
            history.add(new MessageEntry(role, content));
            if (history.size() > MAX_MESSAGES_PER_SESSION) {
                // subList-based remove is O(n) copy but avoids repeated remove(0) shifts
                history.subList(0, history.size() - MAX_MESSAGES_PER_SESSION).clear();
            }
        }
    }

    /** Returns the number of active sessions (useful for monitoring/tests). */
    public int sessionCount() {
        return sessions.size();
    }

    /** Clears all sessions. Intended for use in tests only. */
    void clearAll() {
        sessions.clear();
    }
}
