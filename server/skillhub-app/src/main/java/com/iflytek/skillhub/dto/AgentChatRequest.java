package com.iflytek.skillhub.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AgentChatRequest(
        @JsonProperty("session_id")
        String sessionId,

        @NotBlank(message = "{validation.agent.message.required}")
        @Size(max = 4000, message = "{validation.agent.message.size}")
        String message,

        @NotBlank(message = "{validation.agent.mode.required}")
        String mode,

        @Valid
        AgentChatContextRequest context
) {

    public record AgentChatContextRequest(
            @NotBlank(message = "{validation.agent.source.required}")
            String source,

            @Valid
            TicketDraftRequest ticketDraft,

            @Valid
            AgentUserContextRequest user
    ) {}

    public record TicketDraftRequest(
            String title,
            String description,
            String namespace,
            String mode
    ) {}

    public record AgentUserContextRequest(
            String userId
    ) {}
}
