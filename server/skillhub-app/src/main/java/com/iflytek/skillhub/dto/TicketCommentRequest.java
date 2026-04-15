package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TicketCommentRequest(
        @NotBlank(message = "{validation.ticket.comment.required}")
        @Size(max = 500, message = "{validation.ticket.comment.size}")
        String content
) {
}
