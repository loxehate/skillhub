package com.iflytek.skillhub.dto;

public record AgentSseEventResponse(
        String type,
        Object payload
) {}
