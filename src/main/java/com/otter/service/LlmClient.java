package com.otter.service;

public interface LlmClient {

    /**
     * Single-shot completion with system + user prompt. JSON-mode if system prompt requests JSON.
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * Multi-turn chat: history is an ordered list of {role, content}.
     * Implementations should NOT use JSON-mode here (free-form natural language).
     */
    String chat(String systemPrompt, java.util.List<ChatTurn> history);

    String modelName();

    record ChatTurn(String role, String content) {}
}
