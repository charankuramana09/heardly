package com.otter.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transcripts", indexes = {
    @Index(name = "idx_transcripts_recording_id", columnList = "recording_id", unique = true)
})
public class Transcript {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "recording_id", nullable = false, columnDefinition = "uuid")
    private UUID recordingId;

    @Column(length = 16)
    private String language;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @Column(name = "full_text", columnDefinition = "TEXT")
    private String fullText;

    @Column(name = "segments_json", columnDefinition = "TEXT")
    private String segmentsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getRecordingId() { return recordingId; }
    public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public Double getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Double durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getFullText() { return fullText; }
    public void setFullText(String fullText) { this.fullText = fullText; }

    public String getSegmentsJson() { return segmentsJson; }
    public void setSegmentsJson(String segmentsJson) { this.segmentsJson = segmentsJson; }

    public Instant getCreatedAt() { return createdAt; }
}
