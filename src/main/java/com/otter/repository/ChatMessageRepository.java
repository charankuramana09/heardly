package com.otter.repository;

import com.otter.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findAllByRecordingIdAndUserIdOrderByCreatedAtAsc(UUID recordingId, UUID userId);

    long deleteByRecordingIdAndUserId(UUID recordingId, UUID userId);

    List<ChatMessage> findAllByUserIdAndRecordingIdIsNullOrderByCreatedAtAsc(UUID userId);

    long deleteByUserIdAndRecordingIdIsNull(UUID userId);
}
