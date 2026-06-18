package com.otter.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "insights", indexes = {
    @Index(name = "idx_insights_recording_id", columnList = "recording_id", unique = true)
})
public class Insight {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "recording_id", nullable = false, columnDefinition = "uuid")
    private UUID recordingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InsightStatus status;

    @Column(name = "smart_title", length = 256)
    private String smartTitle;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "action_items_json", columnDefinition = "TEXT")
    private String actionItemsJson;

    @Column(name = "key_topics_json", columnDefinition = "TEXT")
    private String keyTopicsJson;

    @Column(name = "decisions_json", columnDefinition = "TEXT")
    private String decisionsJson;

    @Column(name = "model_name", length = 64)
    private String modelName;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getRecordingId() { return recordingId; }
    public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }

    public InsightStatus getStatus() { return status; }
    public void setStatus(InsightStatus status) { this.status = status; }

    public String getSmartTitle() { return smartTitle; }
    public void setSmartTitle(String smartTitle) { this.smartTitle = smartTitle; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getActionItemsJson() { return actionItemsJson; }
    public void setActionItemsJson(String actionItemsJson) { this.actionItemsJson = actionItemsJson; }

    public String getKeyTopicsJson() { return keyTopicsJson; }
    public void setKeyTopicsJson(String keyTopicsJson) { this.keyTopicsJson = keyTopicsJson; }

    public String getDecisionsJson() { return decisionsJson; }
    public void setDecisionsJson(String decisionsJson) { this.decisionsJson = decisionsJson; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
