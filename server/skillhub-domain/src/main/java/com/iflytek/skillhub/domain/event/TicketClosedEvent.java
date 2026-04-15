package com.iflytek.skillhub.domain.event;

public record TicketClosedEvent(
        Long ticketId,
        String title,
        Long namespaceId,
        String creatorId,
        String status,
        Long skillId,
        Long versionId
) {
}
