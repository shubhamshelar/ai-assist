package com.shubham.aiassistant.web.dto;

/** Response payload for POST /upload. */
public record UploadResponse(String documentId, String filename, boolean duplicate) {}
