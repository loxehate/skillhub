package com.iflytek.skillhub.domain.event;

public record TicketCreatedEvent(
        Long ticketId,
        String title,
        Long namespaceId,
        String creatorId
) {
}
