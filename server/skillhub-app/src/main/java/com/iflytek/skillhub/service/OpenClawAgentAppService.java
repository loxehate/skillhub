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

        OpenAiChatCompletionResponse response = webClient.post()
                .uri(buildChatCompletionUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::applyAuth)
                .bodyValue(new OpenAiChatCompletionRequest(
                        properties.getModel(),
                        false,
                        List.of(
                                new OpenAiMessage("system", buildSystemPrompt()),
                                new OpenAiMessage("user", buildUserPrompt(request, actorUserId))
                        )
                ))
                .retrieve()
                .bodyToMono(OpenAiChatCompletionResponse.class)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .block();

        return parseSuggestion(extractContent(response), draft);
    }

    public String chat(AgentChatRequest request, String actorUserId) {
        ensureEnabled();
        String mode = trimToEmpty(request.mode());
        if (!"general_chat".equalsIgnoreCase(mode) && !"ticket_assistant".equalsIgnoreCase(mode)) {
            throw new DomainBadRequestException("error.agent.mode.unsupported", request.mode());
        }

        return extractContent(webClient.post()
                .uri(buildChatCompletionUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::applyAuth)
                .bodyValue(new OpenAiChatCompletionRequest(
                        properties.getModel(),
                        false,
                        List.of(
                                new OpenAiMessage("system", buildGeneralSystemPrompt(mode)),
                                new OpenAiMessage("user", buildGeneralUserPrompt(request, actorUserId))
                        )
                ))
                .retrieve()
                .bodyToMono(OpenAiChatCompletionResponse.class)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .block());
    }

    public void streamChat(AgentChatRequest request, String actorUserId, Consumer<String> onDelta) {
        ensureEnabled();
        String mode = trimToEmpty(request.mode());
        if (!"general_chat".equalsIgnoreCase(mode) && !"ticket_assistant".equalsIgnoreCase(mode)) {
            throw new DomainBadRequestException("error.agent.mode.unsupported", request.mode());
        }

        try {
            String requestJson = objectMapper.writeValueAsString(new OpenAiChatCompletionRequest(
                    properties.getModel(),
                    true,
                    List.of(
                            new OpenAiMessage("system", buildGeneralSystemPrompt(mode)),
                            new OpenAiMessage("user", buildGeneralUserPrompt(request, actorUserId))
                    )
            ));

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(buildChatCompletionUrl()))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, "*/*")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));
            applyAuth(builder);

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
                        onDelta.accept(delta);
                    }
                }
            }

            if (!emitted) {
                String fallback = chat(request, actorUserId);
                if (StringUtils.hasText(fallback)) {
                    log.info("OpenClaw fallback response: {}", abbreviateForLog(fallback));
                    emitChunkedFallback(fallback, onDelta);
                }
            }
        } catch (DomainBadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Streaming OpenClaw chat failed, falling back to non-stream response: {}", ex.getMessage());
            String fallback = chat(request, actorUserId);
            if (StringUtils.hasText(fallback)) {
                log.info("OpenClaw fallback response after stream failure: {}", abbreviateForLog(fallback));
                emitChunkedFallback(fallback, onDelta);
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

    private String buildChatCompletionUrl() {
        String base = properties.getApiBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
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

    private String buildGeneralSystemPrompt(String mode) {
        if ("ticket_assistant".equalsIgnoreCase(mode)) {
            return """
                    You are SkillHub's ticket assistant.

                    Help users understand ticket workflow, development steps, review expectations,
                    publishing flow, and next actions. Give concise and practical answers.
                    If the context is incomplete, clearly say what extra information is needed.
                    """;
        }

        return """
                You are SkillHub's built-in AI assistant.

                Help users answer questions about SkillHub, skills, namespaces, publishing,
                reviews, tickets, governance, and platform workflows.
                Keep answers concise, practical, and aligned with the current platform behavior.
                If the question depends on missing context, clearly say what is missing.
                """;
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

    private String buildGeneralUserPrompt(AgentChatRequest request, String actorUserId) {
        AgentChatRequest.TicketDraftRequest draft = request.context() != null ? request.context().ticketDraft() : null;
        String title = draft != null ? trimToEmpty(draft.title()) : "";
        String description = draft != null ? trimToEmpty(draft.description()) : "";
        String namespace = draft != null ? trimToEmpty(draft.namespace()) : "";
        String ticketMode = draft != null ? trimToEmpty(draft.mode()) : "";

        return """
                User question: %s

                Current user: %s
                Context source: %s
                Ticket title: %s
                Ticket description: %s
                Ticket namespace: %s
                Ticket mode: %s
                """.formatted(
                trimToEmpty(request.message()),
                actorUserId,
                request.context() != null ? trimToEmpty(request.context().source()) : "",
                title,
                description,
                namespace,
                ticketMode
        );
    }

    private String extractContent(OpenAiChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new DomainBadRequestException("error.agent.response.invalid");
        }

        OpenAiChatCompletionResponse.Choice choice = response.choices().get(0);
        String content = choice != null && choice.message() != null ? choice.message().content() : null;
        if (!StringUtils.hasText(content)) {
            throw new DomainBadRequestException("error.agent.response.invalid");
        }
        return content;
    }

    private String extractStreamDelta(String rawData) {
        try {
            JsonNode root = objectMapper.readTree(rawData);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return "";
            }

            JsonNode firstChoice = choices.get(0);
            JsonNode delta = firstChoice.get("delta");
            if (delta != null) {
                String content = text(delta, "content", "");
                if (StringUtils.hasText(content)) {
                    return content;
                }
            }

            JsonNode message = firstChoice.get("message");
            if (message != null) {
                return text(message, "content", "");
            }

            return "";
        } catch (Exception ex) {
            log.debug("Failed to parse streamed OpenClaw delta: {}", ex.getMessage());
            return "";
        }
    }

    private void emitChunkedFallback(String content, Consumer<String> onDelta) {
        String text = trimToEmpty(content);
        if (!StringUtils.hasText(text)) {
            return;
        }

        int step = 24;
        for (int index = 0; index < text.length(); index += step) {
            int end = Math.min(text.length(), index + step);
            onDelta.accept(text.substring(index, end));
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

    private record OpenAiChatCompletionRequest(
            String model,
            boolean stream,
            List<OpenAiMessage> messages
    ) {}

    private record OpenAiMessage(
            String role,
            String content
    ) {}

    private record OpenAiChatCompletionResponse(
            List<Choice> choices
    ) {
        private record Choice(
                Message message
        ) {}

        private record Message(
                String content
        ) {}
    }
}
