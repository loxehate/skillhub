package com.iflytek.skillhub.domain.event;

public record TicketClaimedEvent(
        Long ticketId,
        String title,
        Long namespaceId,
        String creatorId,
        String claimerId,
        Long teamId
) {
}
