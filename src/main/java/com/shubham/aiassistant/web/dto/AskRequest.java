package com.shubham.aiassistant.web.dto;

/** Request payload for POST /ask. */
public record AskRequest(String question, String documentId, String sessionId) {}
