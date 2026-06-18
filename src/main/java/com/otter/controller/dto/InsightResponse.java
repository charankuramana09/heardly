package com.otter.controller.dto;

import com.otter.domain.Insight;
import com.otter.domain.InsightStatus;
import com.otter.service.InsightService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InsightResponse {
    private UUID id;
    private UUID recordingId;
    private InsightStatus status;
    private String smartTitle;
    private String summary;
    private List<String> keyTopics;
    private List<String> decisions;
    private List<Map<String, Object>> actionItems;
    private String modelName;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public static InsightResponse from(Insight i, InsightService svc) {
        InsightResponse r = new InsightResponse();
        r.id = i.getId();
        r.recordingId = i.getRecordingId();
        r.status = i.getStatus();
        r.smartTitle = i.getSmartTitle();
        r.summary = i.getSummary();
        r.keyTopics = svc.parseStringList(i.getKeyTopicsJson());
        r.decisions = svc.parseStringList(i.getDecisionsJson());
        r.actionItems = svc.parseActionItems(i.getActionItemsJson());
        r.modelName = i.getModelName();
        r.errorMessage = i.getErrorMessage();
        r.createdAt = i.getCreatedAt();
        r.updatedAt = i.getUpdatedAt();
        return r;
    }

    public UUID getId() { return id; }
    public UUID getRecordingId() { return recordingId; }
    public InsightStatus getStatus() { return status; }
    public String getSmartTitle() { return smartTitle; }
    public String getSummary() { return summary; }
    public List<String> getKeyTopics() { return keyTopics; }
    public List<String> getDecisions() { return decisions; }
    public List<Map<String, Object>> getActionItems() { return actionItems; }
    public String getModelName() { return modelName; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
