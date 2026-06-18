package com.otter.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
@Primary
public class OpenAiClient implements LlmClient {

    private final RestClient client;
    private final String model;
    private final boolean enabled;

    public OpenAiClient(
        @Value("${otterfree.openai.base-url:https://api.openai.com/v1}") String baseUrl,
        @Value("${otterfree.openai.api-key:}") String apiKey,
        @Value("${otterfree.openai.model:gpt-4o-mini}") String model
    ) {
        this.model = model;
        this.enabled = apiKey != null && !apiKey.isBlank();
        this.client = enabled
            ? RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
            : null;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!enabled) {
            throw new IllegalStateException(
                "OpenAI API key not configured (otterfree.openai.api-key). " +
                "AI insights are disabled until a key is set.");
        }
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "response_format", Map.of("type", "json_object"),
            "temperature", 0.3
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = client.post()
            .uri("/chat/completions")
            .body(body)
            .retrieve()
            .body(Map.class);

        if (resp == null) throw new IllegalStateException("Empty response from OpenAI");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("No choices in OpenAI response");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) throw new IllegalStateException("No message in OpenAI choice");
        Object content = message.get("content");
        if (content == null) throw new IllegalStateException("No content in OpenAI message");
        return content.toString();
    }

    @Override
    public String chat(String systemPrompt, List<ChatTurn> history) {
        if (!enabled) {
            throw new IllegalStateException(
                "OpenAI API key not configured. Heardly Chat is disabled until a key is set.");
        }
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (ChatTurn t : history) {
            messages.add(Map.of("role", t.role(), "content", t.content()));
        }

        Map<String, Object> body = Map.of(
            "model", model,
            "messages", messages,
            "temperature", 0.3
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = client.post()
            .uri("/chat/completions")
            .body(body)
            .retrieve()
            .body(Map.class);

        if (resp == null) throw new IllegalStateException("Empty response from OpenAI");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("No choices");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        Object content = message == null ? null : message.get("content");
        if (content == null) throw new IllegalStateException("No content");
        return content.toString();
    }

    @Override
    public String modelName() { return model; }

    public boolean isEnabled() { return enabled; }
}
