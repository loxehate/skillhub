package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.Size;

public record TicketRejectRequest(
        @Size(max = 500, message = "{validation.ticket.comment.size}")
        String comment
) {}
