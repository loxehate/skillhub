package com.iflytek.skillhub.dto;

import java.time.Instant;

public record TicketCommentResponse(
        Long id,
        Long ticketId,
        String authorId,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
}
