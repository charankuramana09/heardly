package com.otter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class RecallClient {

    private static final Logger log = LoggerFactory.getLogger(RecallClient.class);

    private final RestClient client;

    public RecallClient(
        @Value("${otterfree.recall.base-url}") String baseUrl,
        @Value("${otterfree.recall.api-key}") String apiKey
    ) {
        this.client = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Token " + apiKey)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createBot(String meetingUrl, String botName) {
        Map<String, Object> body = Map.of(
            "meeting_url", meetingUrl,
            "bot_name", botName,
            "recording_config", Map.of(
                "audio_mixed_mp3", Map.of()
            )
        );
        return client.post()
            .uri("/bot/")
            .body(body)
            .retrieve()
            .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getBot(String botId) {
        return client.get()
            .uri("/bot/{id}/", botId)
            .retrieve()
            .body(Map.class);
    }

    public void leaveBot(String botId) {
        try {
            client.post()
                .uri("/bot/{id}/leave_call/", botId)
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to call leave_call for bot {}: {}", botId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public String extractStatusCode(Map<String, Object> botResponse) {
        Object statusChanges = botResponse.get("status_changes");
        if (statusChanges instanceof List<?> changes && !changes.isEmpty()) {
            Object latest = changes.get(changes.size() - 1);
            if (latest instanceof Map<?, ?> m) {
                Object code = m.get("code");
                if (code != null) return code.toString();
            }
        }
        Object directStatus = botResponse.get("status");
        if (directStatus instanceof Map<?, ?> m) {
            Object code = m.get("code");
            if (code != null) return code.toString();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public String extractAudioDownloadUrl(Map<String, Object> botResponse) {
        Object recordings = botResponse.get("recordings");
        if (!(recordings instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> rec)) return null;
        Object shortcuts = rec.get("media_shortcuts");
        if (!(shortcuts instanceof Map<?, ?> sc)) return null;
        Object audioMixed = sc.get("audio_mixed");
        if (!(audioMixed instanceof Map<?, ?> am)) return null;
        Object data = am.get("data");
        if (!(data instanceof Map<?, ?> d)) return null;
        Object url = d.get("download_url");
        return url == null ? null : url.toString();
    }
}
