package com.otter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class InsightEventListener {

    private static final Logger log = LoggerFactory.getLogger(InsightEventListener.class);

    private final InsightService insightService;
    private final OpenAiClient openAiClient;
    private final IntegrationService integrationService;

    public InsightEventListener(InsightService insightService, OpenAiClient openAiClient,
                                IntegrationService integrationService) {
        this.insightService = insightService;
        this.openAiClient = openAiClient;
        this.integrationService = integrationService;
    }

    @EventListener
    public void onTranscriptionCompleted(TranscriptionCompletedEvent event) {
        if (!openAiClient.isEnabled()) {
            log.info("OpenAI not configured; skipping AI insight generation for {}", event.recordingId());
            return;
        }
        log.info("Triggering AI insight generation for recording {}", event.recordingId());
        insightService.generateAsync(event.recordingId());
    }

    @EventListener
    public void onInsightGenerated(InsightGeneratedEvent event) {
        // Cross-bean call so the @Async / @Transactional proxy on dispatchSummary applies.
        integrationService.dispatchSummary(event.recordingId());
    }
}
