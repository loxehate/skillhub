package com.iflytek.skillhub.domain.event;

public record TicketSkillSubmittedEvent(
        Long ticketId,
        String title,
        Long namespaceId,
        String creatorId,
        String submitterId,
        Long skillId,
        Long versionId
) {
}
