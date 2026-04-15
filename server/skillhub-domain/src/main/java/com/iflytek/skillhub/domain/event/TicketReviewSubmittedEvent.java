package com.iflytek.skillhub.domain.event;

public record TicketReviewSubmittedEvent(
        Long ticketId,
        String title,
        Long namespaceId,
        String creatorId,
        String submitterId
) {
}
