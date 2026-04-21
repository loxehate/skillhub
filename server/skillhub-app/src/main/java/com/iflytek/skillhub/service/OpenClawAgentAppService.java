package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.OpenClawAgentProperties;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.dto.AgentChatRequest;
import com.iflytek.skillhub.dto.TicketAnalyzeSuggestionResponse;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Version;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class OpenClawAgentAppService {

    private static final Logger log = LoggerFactory.getLogger(OpenClawAgentAppService.class);
    private static final String OPENCLAW_MODEL = "openclaw";

    private final OpenClawAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final HttpClient httpClient;

    public OpenClawAgentAppService(OpenClawAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
        this.httpClient = HttpClient.newBuilder()
                .version(Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .build();
    }

    public String resolveSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId.trim() : "agent-" + UUID.randomUUID();
    }

    public TicketAnalyzeSuggestionResponse analyzeTicket(AgentChatRequest request, String actorUserId) {
        ensureEnabled();
        if (!"ticket_analyze".equalsIgnoreCase(request.mode())) {
            throw new DomainBadRequestException("error.agent.mode.unsupported", request.mode());
        }

        AgentChatRequest.TicketDraftRequest draft = request.context() != null ? request.context().ticketDraft() : null;
        String description = draft != null ? trimToEmpty(draft.description()) : "";
        if (!StringUtils.hasText(description)) {
            throw new DomainBadRequestException("error.agent.description.required");
        }

        OpenClawResponse response = webClient.post()
                .uri(buildResponsesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyHeaders(headers, actorUserId, request, resolveSessionId(request.sessionId())))
                .bodyValue(new OpenClawResponsesRequest(
                        false,
                        OPENCLAW_MODEL,
                        buildAnalyzeInput(request, actorUserId),
                        actorUserId
                ))
                .retrieve()
                .bodyToMono(OpenClawResponse.class)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .block();

        return parseSuggestion(extractContent(response), draft);
    }

    public String chat(AgentChatRequest request, String actorUserId, String resolvedSessionId) {
        return chat(resolveConversationHistory(request), request, actorUserId, resolvedSessionId);
    }

    public String chat(List<ConversationTurn> history,
                       AgentChatRequest request,
                       String actorUserId,
                       String resolvedSessionId) {
        ensureEnabled();
        String mode = trimToEmpty(request.mode());
        if (!"general_chat".equalsIgnoreCase(mode) && !"ticket_assistant".equalsIgnoreCase(mode)) {
            throw new DomainBadRequestException("error.agent.mode.unsupported", request.mode());
        }

        return extractContent(webClient.post()
                .uri(buildResponsesUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyHeaders(headers, actorUserId, request, resolvedSessionId))
                .bodyValue(new OpenClawResponsesRequest(
                        false,
                        OPENCLAW_MODEL,
                        buildChatInput(history, request, actorUserId),
                        actorUserId
                ))
                .retrieve()
                .bodyToMono(OpenClawResponse.class)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .block());
    }

    public void streamChat(AgentChatRequest request,
                           String actorUserId,
                           String resolvedSessionId,
                           Consumer<String> onEventData) {
        streamChat(resolveConversationHistory(request), request, actorUserId, resolvedSessionId, onEventData);
    }

    public void streamChat(List<ConversationTurn> history,
                           AgentChatRequest request,
                           String actorUserId,
                           String resolvedSessionId,
                           Consumer<String> onEventData) {
        ensureEnabled();
        String mode = trimToEmpty(request.mode());
        if (!"general_chat".equalsIgnoreCase(mode) && !"ticket_assistant".equalsIgnoreCase(mode)) {
            throw new DomainBadRequestException("error.agent.mode.unsupported", request.mode());
        }

        try {
            String requestJson = objectMapper.writeValueAsString(new OpenClawResponsesRequest(
                    true,
                    OPENCLAW_MODEL,
                    buildChatInput(history, request, actorUserId),
                    actorUserId
            ));

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(buildResponsesUrl()))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, "*/*")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));
            applyHeaders(builder, actorUserId, request, resolvedSessionId);

            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Upstream agent request failed: HTTP " + response.statusCode());
            }

            boolean emitted = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith(":")) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }

                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }

                    String delta = extractStreamDelta(data);
                    if (StringUtils.hasText(delta)) {
                        emitted = true;
                        log.info("OpenClaw stream delta: {}", abbreviateForLog(delta));
                    }
                    onEventData.accept("data: " + data + "\n\n");
                }
            }

            if (!emitted) {
                String fallback = chat(history, request, actorUserId, resolvedSessionId);
                if (StringUtils.hasText(fallback)) {
                    log.info("OpenClaw fallback response: {}", abbreviateForLog(fallback));
                    emitChunkedFallback(fallback, onEventData);
                }
            }
        } catch (DomainBadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Streaming OpenClaw chat failed, falling back to non-stream response: {}", ex.getMessage());
            String fallback = chat(history, request, actorUserId, resolvedSessionId);
            if (StringUtils.hasText(fallback)) {
                log.info("OpenClaw fallback response after stream failure: {}", abbreviateForLog(fallback));
                emitChunkedFallback(fallback, onEventData);
            }
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new DomainBadRequestException("error.agent.disabled");
        }
        if (!properties.isApiConfigured()) {
            throw new DomainBadRequestException("error.agent.config.invalid");
        }
    }

    private void applyAuth(HttpHeaders headers) {
        if (StringUtils.hasText(properties.getApiKey())) {
            headers.setBearerAuth(properties.getApiKey().trim());
        }
    }

    private void applyAuth(HttpRequest.Builder builder) {
        if (StringUtils.hasText(properties.getApiKey())) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey().trim());
        }
    }

    private void applyHeaders(HttpHeaders headers,
                              String actorUserId,
                              AgentChatRequest request,
                              String resolvedSessionId) {
        applyAuth(headers);
        headers.set("x-openclaw-agent-id", resolveAgentId());
        headers.set("x-openclaw-session-key", buildOpenClawSessionKey(actorUserId, request, resolvedSessionId));
    }

    private void applyHeaders(HttpRequest.Builder builder,
                              String actorUserId,
                              AgentChatRequest request,
                              String resolvedSessionId) {
        applyAuth(builder);
        builder.header("x-openclaw-agent-id", resolveAgentId());
        builder.header("x-openclaw-session-key", buildOpenClawSessionKey(actorUserId, request, resolvedSessionId));
    }

    private String buildResponsesUrl() {
        String base = properties.getApiBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/responses";
    }

    private String resolveChatId(AgentChatRequest request) {
        if (request != null && StringUtils.hasText(request.chatId())) {
            return request.chatId().trim();
        }
        if (request != null && StringUtils.hasText(request.sessionId())) {
            return "chat-" + request.sessionId().trim();
        }
        return "chat-" + UUID.randomUUID();
    }

    private String resolveAgentId() {
        return StringUtils.hasText(properties.getAgentId()) ? properties.getAgentId().trim() : "main";
    }

    private String buildOpenClawSessionKey(String actorUserId,
                                           AgentChatRequest request,
                                           String resolvedSessionId) {
        return "%s:%s:%s".formatted(
                trimToEmpty(actorUserId),
                resolveChatId(request),
                trimToEmpty(resolvedSessionId)
        );
    }

    private List<ConversationTurn> resolveConversationHistory(AgentChatRequest request) {
        if (request != null && request.messages() != null && !request.messages().isEmpty()) {
            ArrayList<ConversationTurn> turns = new ArrayList<>();
            for (AgentChatRequest.ConversationTurnRequest turn : request.messages()) {
                if (turn == null || !StringUtils.hasText(turn.role()) || !StringUtils.hasText(turn.content())) {
                    continue;
                }
                turns.add(new ConversationTurn(turn.role().trim(), turn.content().trim()));
            }
            if (!turns.isEmpty()) {
                return List.copyOf(turns);
            }
        }
        return List.of(new ConversationTurn("user", trimToEmpty(request != null ? request.message() : "")));
    }

    private String buildSystemPrompt() {
        return """
                You are SkillHub's platform skill data analysis assistant.

                Your task is to analyze a ticket draft and return strict JSON for the Analyze feature.

                Rules:
                1. Output JSON only. Do not output markdown, explanations, or code fences.
                2. Every field must exist. Do not output null.
                3. amount must be an integer. mode must be either BOUNTY or ASSIGN.
                4. If namespace cannot be inferred, default to global.
                5. The response must include:
                   - summary: string
                   - suggestedTitle: string
                   - mode: "BOUNTY" | "ASSIGN"
                   - namespace: string
                   - amount: integer
                   - completenessIssues: string[]
                   - rationalityAssessment: string
                   - estimatedComplexity: string
                   - estimatedEffort: string
                   - suggestedRewardRange: string
                   - similarSkills: string[]
                   - developmentOutline: string[]
                   - acceptanceCriteria: string[]
                   - riskPoints: string[]
                   - clarificationQuestions: string[]
                6. completenessIssues should list missing or unclear requirement items.
                7. rationalityAssessment should judge feasibility and conflicts with existing capabilities.
                8. similarSkills should list similar skills or reusable directions.
                9. developmentOutline should propose an initial implementation outline.
                10. acceptanceCriteria must be actionable and testable.
                """;
    }

    private String buildAnalyzeInput(AgentChatRequest request, String actorUserId) {
        return buildSystemPrompt() + "\n\n" + buildUserPrompt(request, actorUserId);
    }

    private String buildChatInput(List<ConversationTurn> history,
                                  AgentChatRequest request,
                                  String actorUserId) {
        StringBuilder builder = new StringBuilder();
        builder.append(buildGeneralSystemPrompt(trimToEmpty(request.mode()), request, actorUserId)).append("\n\n");
        for (ConversationTurn turn : history) {
            if (turn == null || !StringUtils.hasText(turn.role()) || !StringUtils.hasText(turn.content())) {
                continue;
            }
            String role = turn.role().trim().toLowerCase();
            if (!Objects.equals(role, "user") && !Objects.equals(role, "assistant")) {
                continue;
            }
            builder.append(role).append(": ").append(turn.content().trim()).append("\n");
        }
        return builder.toString().trim();
    }

    private String buildGeneralSystemPrompt(String mode, AgentChatRequest request, String actorUserId) {
        String contextSummary = buildContextSummary(request, actorUserId);
        if ("ticket_assistant".equalsIgnoreCase(mode)) {
            return """
                    You are SkillHub's ticket assistant.

                    Help users understand ticket workflow, development steps, review expectations,
                    publishing flow, and next actions. Give concise and practical answers.
                    If the context is incomplete, clearly say what extra information is needed.
                    
                    Current context:
                    %s
                    """.formatted(contextSummary);
        }

        return """
                You are SkillHub's built-in AI assistant.

                Help users answer questions about SkillHub, skills, namespaces, publishing,
                reviews, tickets, governance, and platform workflows.
                Keep answers concise, practical, and aligned with the current platform behavior.
                If the question depends on missing context, clearly say what is missing.
                
                Current context:
                %s
                """.formatted(contextSummary);
    }

    private String buildUserPrompt(AgentChatRequest request, String actorUserId) {
        AgentChatRequest.TicketDraftRequest draft = request.context() != null ? request.context().ticketDraft() : null;
        String title = draft != null ? trimToEmpty(draft.title()) : "";
        String description = draft != null ? trimToEmpty(draft.description()) : "";
        String namespace = draft != null && StringUtils.hasText(draft.namespace()) ? draft.namespace().trim() : "global";
        String mode = draft != null && StringUtils.hasText(draft.mode()) ? draft.mode().trim() : "BOUNTY";

        return """
                Analyze the following SkillHub ticket draft and return JSON:

                Current user: %s
                Current source: %s
                Title: %s
                Description: %s
                Namespace: %s
                Mode: %s
                Extra instruction: %s

                Focus on:
                1. Requirement completeness
                2. Requirement rationality and feasibility
                3. Cost and timeline estimation
                4. Similar skill matching
                5. Development outline generation
                """.formatted(
                actorUserId,
                request.context() != null ? trimToEmpty(request.context().source()) : "",
                title,
                description,
                namespace,
                mode,
                trimToEmpty(request.message())
        );
    }

    private String buildContextSummary(AgentChatRequest request, String actorUserId) {
        AgentChatRequest.TicketDraftRequest draft = request.context() != null ? request.context().ticketDraft() : null;
        String title = draft != null ? trimToEmpty(draft.title()) : "";
        String description = draft != null ? trimToEmpty(draft.description()) : "";
        String namespace = draft != null ? trimToEmpty(draft.namespace()) : "";
        String ticketMode = draft != null ? trimToEmpty(draft.mode()) : "";

        return """
                Current user: %s
                Context source: %s
                Ticket title: %s
                Ticket description: %s
                Ticket namespace: %s
                Ticket mode: %s
                """.formatted(
                actorUserId,
                request.context() != null ? trimToEmpty(request.context().source()) : "",
                title,
                description,
                namespace,
                ticketMode
        );
    }

    private String extractContent(OpenClawResponse response) {
        if (response == null) {
            throw new DomainBadRequestException("error.agent.response.invalid");
        }
        String content = response.outputText();
        if (!StringUtils.hasText(content)) {
            throw new DomainBadRequestException("error.agent.response.invalid");
        }
        return content;
    }

    private String extractStreamDelta(String rawData) {
        try {
            JsonNode root = objectMapper.readTree(rawData);
            String type = text(root, "type", "");
            if ("response.output_text.delta".equals(type)) {
                return text(root, "delta", "");
            }
            if ("response.output_text.done".equals(type)) {
                return text(root, "text", "");
            }
            JsonNode delta = root.get("delta");
            if (delta != null) {
                String content = delta.isTextual() ? delta.asText("") : text(delta, "content", "");
                if (StringUtils.hasText(content)) {
                    return content;
                }
            }
            JsonNode output = root.get("output");
            if (output != null && output.isArray()) {
                for (JsonNode item : output) {
                    JsonNode contentItems = item.get("content");
                    if (contentItems != null && contentItems.isArray()) {
                        for (JsonNode contentItem : contentItems) {
                            String candidate = text(contentItem, "text", "");
                            if (StringUtils.hasText(candidate)) {
                                return candidate;
                            }
                        }
                    }
                }
            }
            return text(root, "output_text", "");
        } catch (Exception ex) {
            log.debug("Failed to parse streamed OpenClaw delta: {}", ex.getMessage());
            return "";
        }
    }

    private void emitChunkedFallback(String content, Consumer<String> onEventData) {
        String text = trimToEmpty(content);
        if (!StringUtils.hasText(text)) {
            return;
        }

        int step = 24;
        for (int index = 0; index < text.length(); index += step) {
            int end = Math.min(text.length(), index + step);
            onEventData.accept("data: {\"type\":\"response.output_text.delta\",\"delta\":%s}\n\n"
                    .formatted(quoteJson(text.substring(index, end))));
        }
    }

    private String quoteJson(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "\"\"";
        }
    }

    private String abbreviateForLog(String value) {
        String text = trimToEmpty(value).replaceAll("\\s+", " ");
        if (text.length() <= 200) {
            return text;
        }
        return text.substring(0, 200) + "...[truncated]";
    }

    private TicketAnalyzeSuggestionResponse parseSuggestion(String rawContent,
                                                           AgentChatRequest.TicketDraftRequest draft) {
        try {
            String json = extractJsonObject(rawContent);
            JsonNode root = objectMapper.readTree(json);

            String fallbackNamespace = draft != null && StringUtils.hasText(draft.namespace())
                    ? draft.namespace().trim()
                    : "global";

            return new TicketAnalyzeSuggestionResponse(
                    text(root, "summary", ""),
                    text(root, "suggestedTitle", text(root, "summary", "")),
                    normalizeMode(text(root, "mode", draft != null ? draft.mode() : "BOUNTY")),
                    text(root, "namespace", fallbackNamespace),
                    integerValue(root.get("amount")),
                    list(root.get("completenessIssues")),
                    text(root, "rationalityAssessment", ""),
                    text(root, "estimatedComplexity", ""),
                    text(root, "estimatedEffort", ""),
                    text(root, "suggestedRewardRange", ""),
                    list(root.get("similarSkills")),
                    list(root.get("developmentOutline")),
                    list(root.get("acceptanceCriteria")),
                    list(root.get("riskPoints")),
                    list(root.get("clarificationQuestions"))
            );
        } catch (Exception ex) {
            log.warn("Failed to parse OpenClaw analysis response: {}", ex.getMessage());
            throw new DomainBadRequestException("error.agent.response.invalid");
        }
    }

    private String extractJsonObject(String rawContent) {
        String trimmed = trimToEmpty(rawContent);
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?", "").trim();
            trimmed = trimmed.replaceFirst("```$", "").trim();
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object found");
        }
        return trimmed.substring(start, end + 1);
    }

    private String normalizeMode(String mode) {
        String normalized = trimToEmpty(mode).toUpperCase();
        return Objects.equals(normalized, "ASSIGN") ? "ASSIGN" : "BOUNTY";
    }

    private Integer integerValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }

        String raw = node.asText("").replaceAll("[^0-9]", "");
        if (!StringUtils.hasText(raw)) {
            return 0;
        }
        return Integer.parseInt(raw);
    }

    private String text(JsonNode root, String fieldName, String defaultValue) {
        if (root == null) {
            return defaultValue;
        }

        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) {
            return defaultValue;
        }

        String value = node.asText("").trim();
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private List<String> list(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            ArrayList<String> values = new ArrayList<>();
            node.forEach(item -> {
                String value = item != null ? item.asText("").trim() : "";
                if (StringUtils.hasText(value)) {
                    values.add(value);
                }
            });
            return List.copyOf(values);
        }

        String single = node.asText("").trim();
        return StringUtils.hasText(single) ? List.of(single) : List.of();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private record OpenClawResponsesRequest(
            boolean stream,
            String model,
            String input,
            String user
    ) {}

    public record ConversationTurn(
            String role,
            String content
    ) {}

    private record OpenClawResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("output_text")
            String outputText
    ) {}
}
