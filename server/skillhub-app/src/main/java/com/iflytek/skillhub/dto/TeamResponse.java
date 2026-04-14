package com.iflytek.skillhub.dto;

import java.time.Instant;

public record TeamResponse(
        Long id,
        String name,
        String ownerId,
        Long namespaceId,
        Instant createdAt,
        Instant updatedAt
) {}
