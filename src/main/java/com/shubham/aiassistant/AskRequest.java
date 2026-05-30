package com.shubham.aiassistant;

public record AskRequest(String question, String documentId, String sessionId) {}
