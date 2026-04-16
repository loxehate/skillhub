package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.config.OpenClawAgentProperties;
import com.iflytek.skillhub.dto.AgentChatRequest;
import com.iflytek.skillhub.dto.TicketAnalyzeSuggestionResponse;
import com.iflytek.skillhub.service.OpenClawAgentAppService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    public AgentController(OpenClawAgentAppService openClawAgentAppService,
                           OpenClawAgentProperties properties) {
        this.openClawAgentAppService = openClawAgentAppService;
        this.properties = properties;
    }

    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody AgentChatRequest request,
                           @AuthenticationPrincipal PlatformPrincipal principal) {
        String sessionId = openClawAgentAppService.resolveSessionId(request.sessionId());
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

                    TicketAnalyzeSuggestionResponse suggestion = openClawAgentAppService.analyzeTicket(request, principal.userId());

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
                    String answer = openClawAgentAppService.chat(request, principal.userId());
                    send(emitter, "assistant_delta", Map.of(
                            "message_id", "assistant_1",
                            "delta", answer
                    ));
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
        emitter.send(SseEmitter.event().name(eventName).data(payload));
    }
}
