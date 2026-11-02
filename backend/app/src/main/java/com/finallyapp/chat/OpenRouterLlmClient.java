package com.finallyapp.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.config.FinallyProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Calls OpenRouter's chat completions API ({@code openai/gpt-oss-120b} served by Cerebras) with a strict
 * JSON-schema response format (CONTRACTS.md §9).
 */
public class OpenRouterLlmClient implements LlmClient {

    private final RestClient client;
    private final FinallyProperties.Llm cfg;
    private final ObjectMapper mapper;

    public OpenRouterLlmClient(RestClient.Builder builder, FinallyProperties.Llm cfg, ObjectMapper mapper) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.client = builder.baseUrl(cfg.baseUrl())
                .defaultHeader("Authorization", "Bearer " + (cfg.apiKey() == null ? "" : cfg.apiKey().trim()))
                .defaultHeader("X-Title", "FinAlly")
                .build();
    }

    @Override
    public String complete(List<Message> messages) {
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw new LlmException("OPENROUTER_API_KEY is not configured (set it in .env, or set LLM_MOCK=true)");
        }
        Map<String, Object> body = requestBody(cfg.model(), cfg.provider(), messages);
        String raw;
        try {
            raw = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new LlmException("LLM request failed: " + e.getMessage(), e);
        }
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                String err = root.path("error").path("message").asText("no content in response");
                throw new LlmException("LLM returned no content: " + err);
            }
            return content.asText();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Unreadable LLM response", e);
        }
    }

    static Map<String, Object> requestBody(String model, String provider, List<Message> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());
        body.put("provider", Map.of("order", List.of(provider)));
        body.put("reasoning", Map.of("effort", "low"));
        body.put("response_format", Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "finally_response",
                        "strict", true,
                        "schema", responseSchema())));
        return body;
    }

    static Map<String, Object> responseSchema() {
        Map<String, Object> trade = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("ticker", "side", "quantity"),
                "properties", Map.of(
                        "ticker", Map.of("type", "string"),
                        "side", Map.of("type", "string", "enum", List.of("buy", "sell")),
                        "quantity", Map.of("type", "number")));
        Map<String, Object> change = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("ticker", "action"),
                "properties", Map.of(
                        "ticker", Map.of("type", "string"),
                        "action", Map.of("type", "string", "enum", List.of("add", "remove"))));
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("message", "trades", "watchlist_changes"),
                "properties", Map.of(
                        "message", Map.of("type", "string"),
                        "trades", Map.of("type", "array", "items", trade),
                        "watchlist_changes", Map.of("type", "array", "items", change)));
    }
}
