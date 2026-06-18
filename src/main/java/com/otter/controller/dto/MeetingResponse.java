package com.otter.controller.dto;

import com.otter.domain.Meeting;
import com.otter.domain.MeetingStatus;

import java.time.Instant;
import java.util.UUID;

public class MeetingResponse {
    private UUID id;
    private String meetingUrl;
    private String botName;
    private String recallBotId;
    private MeetingStatus status;
    private UUID recordingId;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public static MeetingResponse from(Meeting m) {
        MeetingResponse r = new MeetingResponse();
        r.id = m.getId();
        r.meetingUrl = m.getMeetingUrl();
        r.botName = m.getBotName();
        r.recallBotId = m.getRecallBotId();
        r.status = m.getStatus();
        r.recordingId = m.getRecordingId();
        r.errorMessage = m.getErrorMessage();
        r.createdAt = m.getCreatedAt();
        r.updatedAt = m.getUpdatedAt();
        return r;
    }

    public UUID getId() { return id; }
    public String getMeetingUrl() { return meetingUrl; }
    public String getBotName() { return botName; }
    public String getRecallBotId() { return recallBotId; }
    public MeetingStatus getStatus() { return status; }
    public UUID getRecordingId() { return recordingId; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
