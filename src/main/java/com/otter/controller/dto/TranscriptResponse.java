package com.otter.controller.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otter.domain.Transcript;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TranscriptResponse {

    private UUID id;
    private UUID recordingId;
    private String language;
    private Double durationSeconds;
    private String fullText;
    private List<Map<String, Object>> segments;
    private Instant createdAt;

    @SuppressWarnings("unchecked")
    public static TranscriptResponse from(Transcript t, ObjectMapper objectMapper) {
        TranscriptResponse r = new TranscriptResponse();
        r.id = t.getId();
        r.recordingId = t.getRecordingId();
        r.language = t.getLanguage();
        r.durationSeconds = t.getDurationSeconds();
        r.fullText = t.getFullText();
        r.createdAt = t.getCreatedAt();
        try {
            if (t.getSegmentsJson() != null) {
                r.segments = objectMapper.readValue(t.getSegmentsJson(), List.class);
            }
        } catch (JsonProcessingException e) {
            r.segments = List.of();
        }
        return r;
    }

    public UUID getId() { return id; }
    public UUID getRecordingId() { return recordingId; }
    public String getLanguage() { return language; }
    public Double getDurationSeconds() { return durationSeconds; }
    public String getFullText() { return fullText; }
    public List<Map<String, Object>> getSegments() { return segments; }
    public Instant getCreatedAt() { return createdAt; }
}
