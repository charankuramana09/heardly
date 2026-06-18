package com.otter.controller.dto;

import com.otter.domain.Recording;
import com.otter.domain.RecordingStatus;

import java.time.Instant;
import java.util.UUID;

public class RecordingResponse {

    private UUID id;
    private String originalFilename;
    private String contentType;
    private Long sizeBytes;
    private RecordingStatus status;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public static RecordingResponse from(Recording r) {
        RecordingResponse resp = new RecordingResponse();
        resp.id = r.getId();
        resp.originalFilename = r.getOriginalFilename();
        resp.contentType = r.getContentType();
        resp.sizeBytes = r.getSizeBytes();
        resp.status = r.getStatus();
        resp.errorMessage = r.getErrorMessage();
        resp.createdAt = r.getCreatedAt();
        resp.updatedAt = r.getUpdatedAt();
        return resp;
    }

    public UUID getId() { return id; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public RecordingStatus getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
