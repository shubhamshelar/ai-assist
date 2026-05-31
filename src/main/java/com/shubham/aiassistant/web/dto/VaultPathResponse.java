package com.shubham.aiassistant.web.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Response payload for vault path list and add operations. */
public record VaultPathResponse(
    UUID id,
    String path,
    LocalDateTime addedAt,
    LocalDateTime lastScannedAt,
    long fileCount
) {}
