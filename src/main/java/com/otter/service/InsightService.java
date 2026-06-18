package com.otter.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otter.domain.Insight;
import com.otter.domain.InsightStatus;
import com.otter.domain.Recording;
import com.otter.domain.Transcript;
import com.otter.repository.InsightRepository;
import com.otter.repository.RecordingRepository;
import com.otter.repository.TranscriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    private static final String SYSTEM_PROMPT = """
        You are an assistant that extracts structured insights from meeting / call transcripts.
        Always respond with a single valid JSON object matching this schema exactly:
        {
          "title": "<a short descriptive title for this recording, max 80 chars>",
          "summary": "<3-5 sentence overview of what was discussed>",
          "key_topics": ["<topic 1>", "<topic 2>", ...],
          "decisions": ["<decision 1>", "<decision 2>", ...],
          "action_items": [
            {"task": "<what needs to be done>", "owner": "<person responsible or null>", "due": "<deadline phrase or null>"}
          ]
        }
        Rules:
        - title: punchy, no quotes, no trailing punctuation.
        - summary: factual, no opinion, no fluff.
        - key_topics: 3-6 short noun phrases.
        - decisions: only include concrete decisions explicitly made. Empty array if none.
        - action_items: extract concrete tasks. Empty array if none. owner/due may be null.
        - If transcript is too short or empty, still return the JSON with empty arrays and a brief summary.
        """;

    private final InsightRepository insightRepository;
    private final RecordingRepository recordingRepository;
    private final TranscriptRepository transcriptRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final org.springframework.context.ApplicationEventPublisher events;

    public InsightService(
        InsightRepository insightRepository,
        RecordingRepository recordingRepository,
        TranscriptRepository transcriptRepository,
        LlmClient llmClient,
        ObjectMapper objectMapper,
        org.springframework.context.ApplicationEventPublisher events
    ) {
        this.insightRepository = insightRepository;
        this.recordingRepository = recordingRepository;
        this.transcriptRepository = transcriptRepository;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Async
    public void generateAsync(UUID recordingId) {
        try {
            Insight insight = generate(recordingId);
            if (insight.getStatus() == InsightStatus.COMPLETED) {
                // Published after the @Transactional generate() has committed, so the
                // async integration dispatcher always reads the persisted insight.
                events.publishEvent(new InsightGeneratedEvent(recordingId));
            }
        } catch (Exception e) {
            log.error("Insight generation failed for {}", recordingId, e);
        }
    }

    @Transactional
    public Insight generate(UUID recordingId) {
        Recording recording = recordingRepository.findById(recordingId)
            .orElseThrow(() -> new IllegalArgumentException("Recording not found: " + recordingId));
        Transcript transcript = transcriptRepository.findByRecordingId(recordingId)
            .orElseThrow(() -> new IllegalStateException("Transcript not ready for recording: " + recordingId));
        if (transcript.getFullText() == null || transcript.getFullText().isBlank()) {
            throw new IllegalStateException("Transcript is empty");
        }

        Insight insight = insightRepository.findByRecordingId(recordingId).orElseGet(() -> {
            Insight i = new Insight();
            i.setId(UUID.randomUUID());
            i.setRecordingId(recordingId);
            return i;
        });
        insight.setStatus(InsightStatus.PENDING);
        insight.setErrorMessage(null);
        insight = insightRepository.save(insight);

        try {
            String userPrompt = "Transcript:\n\n" + truncate(transcript.getFullText(), 60_000);
            String raw = llmClient.complete(SYSTEM_PROMPT, userPrompt);
            JsonNode parsed = objectMapper.readTree(raw);

            String title = asText(parsed.get("title"));
            String summary = asText(parsed.get("summary"));
            JsonNode topics = parsed.get("key_topics");
            JsonNode decisions = parsed.get("decisions");
            JsonNode actions = parsed.get("action_items");

            insight.setSmartTitle(truncate(title, 256));
            insight.setSummary(summary);
            insight.setKeyTopicsJson(topics != null ? objectMapper.writeValueAsString(topics) : "[]");
            insight.setDecisionsJson(decisions != null ? objectMapper.writeValueAsString(decisions) : "[]");
            insight.setActionItemsJson(actions != null ? objectMapper.writeValueAsString(actions) : "[]");
            insight.setModelName(llmClient.modelName());
            insight.setStatus(InsightStatus.COMPLETED);
            log.info("Generated insights for recording {} with model {}", recordingId, llmClient.modelName());
        } catch (Exception e) {
            log.error("LLM call failed for recording {}", recordingId, e);
            insight.setStatus(InsightStatus.FAILED);
            insight.setErrorMessage(truncate(e.getMessage(), 2000));
        }
        return insightRepository.save(insight);
    }

    @Transactional(readOnly = true)
    public Optional<Insight> getForRecording(UUID recordingId) {
        return insightRepository.findByRecordingId(recordingId);
    }

    public List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> parseActionItems(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String asText(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
