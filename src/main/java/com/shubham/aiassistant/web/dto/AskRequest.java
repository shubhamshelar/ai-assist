package com.shubham.aiassistant.web.dto;

/**
 * Request payload for POST /ask.
 *
 * @param sourceFilter optional filter: "all" (default), "pdf", or "vault"
 */
public record AskRequest(String question, String documentId, String sessionId, String sourceFilter) {}
