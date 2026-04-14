package com.iflytek.skillhub.dto;

import java.time.Instant;

public record TeamMemberResponse(
        Long teamId,
        String userId,
        String role,
        Instant createdAt
) {}
