package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;

public record TeamMemberAddRequest(
        @NotBlank(message = "{validation.team.member.userId.notBlank}")
        String userId,

        @NotBlank(message = "{validation.team.member.role.notBlank}")
        String role
) {}
