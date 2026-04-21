package com.iflytek.skillhub.controller.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.dto.AgentChatRequest;
import com.iflytek.skillhub.dto.TicketAnalyzeSuggestionResponse;
import com.iflytek.skillhub.service.OpenClawAgentAppService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/web/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final OpenClawAgentAppService openClawAgentAppService;
    private final ObjectMapper objectMapper;

    public AgentController(OpenClawAgentAppService openClawAgentAppService,
                           ObjectMapper objectMapper) {
        this.openClawAgentAppService = openClawAgentAppService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(
            path = "/chat",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody String requestBody,
                                                      @AuthenticationPrincipal PlatformPrincipal principal) {
        AgentChatRequest request = parseRequest(requestBody);
        String sessionId = openClawAgentAppService.resolveSessionId(request.sessionId());
        String actorUserId = principal != null ? principal.userId() : "anonymous";
        StreamingResponseBody stream = outputStream -> {
            try {
                send(outputStream, "session_started", Map.of("session_id", sessionId));
                if ("ticket_analyze".equalsIgnoreCase(request.mode())) {
                    send(outputStream, "tool_started", Map.of(
                            "message_id", "tool_1",
                            "tool_name", "platform_skill_data_analysis",
                            "detail", "Calling OpenClaw analysis"
                    ));

                    TicketAnalyzeSuggestionResponse suggestion = openClawAgentAppService.analyzeTicket(request, actorUserId);

                    send(outputStream, "assistant_delta", Map.of(
                            "message_id", "assistant_1",
                            "delta", suggestion.summary() != null && !suggestion.summary().isBlank()
                                    ? suggestion.summary()
                                    : "Analysis complete."
                    ));
                    send(outputStream, "assistant_done", Map.of("message_id", "assistant_1"));
                    send(outputStream, "tool_finished", Map.of(
                            "message_id", "tool_1",
                            "tool_name", "platform_skill_data_analysis",
                            "detail", "OpenClaw analysis completed"
                    ));
                    send(outputStream, "structured_result", Map.of(
                            "type", request.mode(),
                            "payload", suggestion
                    ));
                } else {
                    final String assistantMessageId = "assistant_1";
                    final boolean[] assistantStarted = {false};
                    final boolean[] assistantFinished = {false};
                    openClawAgentAppService.streamChat(request, actorUserId, sessionId, eventData -> {
                        try {
                            String delta = extractOpenClawDelta(eventData);
                            if (StringUtils.hasText(delta)) {
                                send(outputStream, "assistant_delta", Map.of(
                                        "message_id", assistantMessageId,
                                        "delta", delta
                                ));
                                assistantStarted[0] = true;
                            }
                            if (isOpenClawDone(eventData) && assistantStarted[0] && !assistantFinished[0]) {
                                send(outputStream, "assistant_done", Map.of("message_id", assistantMessageId));
                                assistantFinished[0] = true;
                            }
                        } catch (IOException ioException) {
                            throw new RuntimeException(ioException);
                        }
                    });
                    if (assistantStarted[0] && !assistantFinished[0]) {
                        send(outputStream, "assistant_done", Map.of("message_id", assistantMessageId));
                    }
                }

                send(outputStream, "done", Map.of("session_id", sessionId));
            } catch (Exception ex) {
                log.warn("Agent SSE request failed: {}", ex.getMessage());
                try {
                    send(outputStream, "error", Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Agent request failed"));
                } catch (IOException ioException) {
                    log.debug("Unable to send agent error event: {}", ioException.getMessage());
                }
            } finally {
                outputStream.flush();
            }
        };

        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    private void send(OutputStream outputStream,
                      String eventName,
                      Object payload) throws IOException {
        String data = objectMapper.writeValueAsString(payload);
        String chunk = "event: " + eventName + "\n" +
                "data: " + data + "\n\n";
        outputStream.write(chunk.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private String extractOpenClawDelta(String eventData) {
        try {
            Map<?, ?> payload = objectMapper.readValue(eventData, Map.class);
            Object delta = payload.get("delta");
            if (delta instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
            Object outputText = payload.get("output_text");
            if (outputText instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
            Object type = payload.get("type");
            Object text = payload.get("text");
            if ("response.output_text.done".equals(type) && text instanceof String doneText && StringUtils.hasText(doneText)) {
                return doneText;
            }
        } catch (Exception ex) {
            log.debug("Failed to parse OpenClaw stream payload: {}", ex.getMessage());
        }
        return "";
    }

    private boolean isOpenClawDone(String eventData) {
        try {
            Map<?, ?> payload = objectMapper.readValue(eventData, Map.class);
            Object type = payload.get("type");
            return "response.completed".equals(type)
                    || "response.done".equals(type)
                    || "response.output_text.done".equals(type);
        } catch (Exception ex) {
            return false;
        }
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
