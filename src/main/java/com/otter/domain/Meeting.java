package com.otter.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meetings", indexes = {
    @Index(name = "idx_meetings_user_id", columnList = "user_id"),
    @Index(name = "idx_meetings_status", columnList = "status"),
    @Index(name = "idx_meetings_recall_bot_id", columnList = "recall_bot_id")
})
public class Meeting {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", columnDefinition = "uuid", nullable = false)
    private UUID userId;

    @Column(name = "meeting_url", nullable = false, length = 2048)
    private String meetingUrl;

    @Column(name = "bot_name", length = 100)
    private String botName;

    @Column(name = "recall_bot_id", length = 64)
    private String recallBotId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MeetingStatus status;

    @Column(name = "recording_id", columnDefinition = "uuid")
    private UUID recordingId;

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

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getMeetingUrl() { return meetingUrl; }
    public void setMeetingUrl(String meetingUrl) { this.meetingUrl = meetingUrl; }

    public String getBotName() { return botName; }
    public void setBotName(String botName) { this.botName = botName; }

    public String getRecallBotId() { return recallBotId; }
    public void setRecallBotId(String recallBotId) { this.recallBotId = recallBotId; }

    public MeetingStatus getStatus() { return status; }
    public void setStatus(MeetingStatus status) { this.status = status; }

    public UUID getRecordingId() { return recordingId; }
    public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
