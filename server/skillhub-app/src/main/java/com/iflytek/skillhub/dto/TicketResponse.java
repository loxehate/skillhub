package com.iflytek.skillhub.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TicketResponse(
        Long id,
        String title,
        String description,
        String mode,
        BigDecimal reward,
        String status,
        String creatorId,
        Long namespaceId,
        Long targetTeamId,
        Long submitSkillId,
        Long submitSkillVersionId,
        Long skillReviewTaskId,
        Instant createdAt,
        Instant updatedAt
) {}
