package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeamCreateRequest(
        @NotBlank(message = "{validation.team.name.notBlank}")
        @Size(max = 128, message = "{validation.team.name.size}")
        String name,

        @NotBlank(message = "{validation.team.namespace.notBlank}")
        String namespace
) {}
