package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TicketCreateRequest(
        @NotBlank(message = "{validation.ticket.title.notBlank}")
        @Size(max = 200, message = "{validation.ticket.title.size}")
        String title,

        @Size(max = 4000, message = "{validation.ticket.description.size}")
        String description,

        @NotBlank(message = "{validation.ticket.mode.notBlank}")
        String mode,

        BigDecimal reward,

        @NotBlank(message = "{validation.ticket.namespace.notBlank}")
        String namespace,

        Long targetTeamId,

        String targetUserId
) {}
