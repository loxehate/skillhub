package com.iflytek.skillhub.domain.event;

public record TicketRejectedEvent(
        Long ticketId,
        String title,
        Long namespaceId,
        String creatorId,
        String reviewerId
) {
}
