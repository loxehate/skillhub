package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TicketUpdateRequest(
        @NotBlank(message = "{validation.ticket.title.required}")
        @Size(max = 200, message = "{validation.ticket.title.size}")
        String title,

        @Size(max = 4000, message = "{validation.ticket.description.size}")
        String description,

        @NotBlank(message = "{validation.ticket.mode.required}")
        String mode,

        BigDecimal reward,

        @NotBlank(message = "{validation.ticket.namespace.required}")
        String namespace,

        Long targetTeamId
) {
}
