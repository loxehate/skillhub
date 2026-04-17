package com.iflytek.skillhub.controller.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.config.OpenClawAgentProperties;
import com.iflytek.skillhub.dto.AgentChatRequest;
import com.iflytek.skillhub.dto.TicketAnalyzeSuggestionResponse;
import com.iflytek.skillhub.service.OpenClawAgentAppService;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/web/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final OpenClawAgentAppService openClawAgentAppService;
    private final OpenClawAgentProperties properties;
    private final ObjectMapper objectMapper;

    public AgentController(OpenClawAgentAppService openClawAgentAppService,
                           OpenClawAgentProperties properties,
                           ObjectMapper objectMapper) {
        this.openClawAgentAppService = openClawAgentAppService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping(
            path = "/chat",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter chat(@RequestBody String requestBody,
                           @AuthenticationPrincipal PlatformPrincipal principal) {
        AgentChatRequest request = parseRequest(requestBody);
        String sessionId = openClawAgentAppService.resolveSessionId(request.sessionId());
        String actorUserId = principal != null ? principal.userId() : "anonymous";
        SseEmitter emitter = new SseEmitter(properties.getSseTimeoutMs());

        CompletableFuture.runAsync(() -> {
            try {
                send(emitter, "session_started", Map.of("session_id", sessionId));
                if ("ticket_analyze".equalsIgnoreCase(request.mode())) {
                    send(emitter, "tool_started", Map.of(
                            "message_id", "tool_1",
                            "tool_name", "platform_skill_data_analysis",
                            "detail", "Calling OpenClaw analysis"
                    ));

                    TicketAnalyzeSuggestionResponse suggestion = openClawAgentAppService.analyzeTicket(request, actorUserId);

                    send(emitter, "assistant_delta", Map.of(
                            "message_id", "assistant_1",
                            "delta", suggestion.summary() != null && !suggestion.summary().isBlank()
                                    ? suggestion.summary()
                                    : "Analysis complete."
                    ));
                    send(emitter, "assistant_done", Map.of("message_id", "assistant_1"));
                    send(emitter, "tool_finished", Map.of(
                            "message_id", "tool_1",
                            "tool_name", "platform_skill_data_analysis",
                            "detail", "OpenClaw analysis completed"
                    ));
                    send(emitter, "structured_result", Map.of(
                            "type", request.mode(),
                            "payload", suggestion
                    ));
                } else {
                    openClawAgentAppService.streamChat(request, actorUserId, delta -> {
                        try {
                            send(emitter, "assistant_delta", Map.of(
                                    "message_id", "assistant_1",
                                    "delta", delta
                            ));
                        } catch (IOException ioException) {
                            throw new RuntimeException(ioException);
                        }
                    });
                    send(emitter, "assistant_done", Map.of("message_id", "assistant_1"));
                }

                send(emitter, "done", Map.of("session_id", sessionId));
                emitter.complete();
            } catch (Exception ex) {
                log.warn("Agent SSE request failed: {}", ex.getMessage());
                try {
                    send(emitter, "error", Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Agent request failed"));
                } catch (IOException ioException) {
                    log.debug("Unable to send agent error event: {}", ioException.getMessage());
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    private void send(SseEmitter emitter, String eventName, Object payload) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
    }

    private AgentChatRequest parseRequest(String requestBody) {
        try {
            AgentChatRequest request = objectMapper.readValue(requestBody, AgentChatRequest.class);
            validate(request);
            return request;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid agent request payload");
        }
    }

    private void validate(AgentChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Invalid agent request payload");
        }

        if (!StringUtils.hasText(request.message())) {
            throw new IllegalArgumentException("Agent message is required");
        }
        if (request.message().trim().length() > 4000) {
            throw new IllegalArgumentException("Agent message is too long");
        }
        if (!StringUtils.hasText(request.mode())) {
            throw new IllegalArgumentException("Agent mode is required");
        }
        if (request.context() == null || !StringUtils.hasText(request.context().source())) {
            throw new IllegalArgumentException("Agent source is required");
        }
    }
}
