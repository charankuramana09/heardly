package com.otter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Posts messages to a Slack channel via an Incoming Webhook URL.
 * No OAuth needed — each user pastes their own webhook URL when connecting.
 */
@Service
public class SlackClient {

    private static final Logger log = LoggerFactory.getLogger(SlackClient.class);

    private final RestClient client = RestClient.builder()
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build();

    /** Sends Slack mrkdwn text to the given incoming-webhook URL. Throws on failure. */
    public void send(String webhookUrl, String text) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException("Slack webhook URL is required");
        }
        client.post()
            .uri(webhookUrl)
            .body(Map.of("text", text))
            .retrieve()
            .toBodilessEntity();
        log.info("Posted summary to Slack webhook");
    }

    public boolean looksLikeWebhook(String url) {
        return url != null && url.startsWith("https://hooks.slack.com/");
    }
}
