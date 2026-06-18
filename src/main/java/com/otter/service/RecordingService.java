package com.otter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otter.domain.Recording;
import com.otter.domain.RecordingStatus;
import com.otter.domain.Transcript;
import com.otter.repository.ChatMessageRepository;
import com.otter.repository.InsightRepository;
import com.otter.repository.MeetingRepository;
import com.otter.repository.RecordingRepository;
import com.otter.repository.TranscriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class RecordingService {

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);

    private final RecordingRepository recordingRepository;
    private final TranscriptRepository transcriptRepository;
    private final InsightRepository insightRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MeetingRepository meetingRepository;
    private final FileStorageService fileStorageService;
    private final WhisperClient whisperClient;
    private final ObjectMapper objectMapper;
    private final org.springframework.context.ApplicationEventPublisher events;

    public RecordingService(
        RecordingRepository recordingRepository,
        TranscriptRepository transcriptRepository,
        InsightRepository insightRepository,
        ChatMessageRepository chatMessageRepository,
        MeetingRepository meetingRepository,
        FileStorageService fileStorageService,
        WhisperClient whisperClient,
        ObjectMapper objectMapper,
        org.springframework.context.ApplicationEventPublisher events
    ) {
        this.recordingRepository = recordingRepository;
        this.transcriptRepository = transcriptRepository;
        this.insightRepository = insightRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.meetingRepository = meetingRepository;
        this.fileStorageService = fileStorageService;
        this.whisperClient = whisperClient;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Transactional
    public Recording upload(MultipartFile file, UUID userId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required and must not be empty");
        }

        UUID id = UUID.randomUUID();
        FileStorageService.StoredFile stored = fileStorageService.save(file, id);

        Recording r = new Recording();
        r.setId(id);
        r.setUserId(userId);
        r.setStatus(RecordingStatus.UPLOADED);
        r.setOriginalFilename(stored.originalFilename());
        r.setContentType(stored.contentType());
        r.setSizeBytes(stored.sizeBytes());
        r.setAudioFilePath(stored.absolutePath());
        return recordingRepository.save(r);
    }

    @Async
    public void transcribeAsync(UUID recordingId) {
        try {
            markStatus(recordingId, RecordingStatus.TRANSCRIBING, null);
            Recording r = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new NoSuchElementException("Recording missing: " + recordingId));
            Path audioPath = fileStorageService.resolve(r.getAudioFilePath());
            if (!Files.exists(audioPath)) {
                throw new IllegalStateException("Audio file not found on disk: " + audioPath);
            }

            log.info("Transcribing recording {} ({} bytes)", recordingId, r.getSizeBytes());
            WhisperClient.TranscriptionResult result = whisperClient.transcribe(audioPath);

            Transcript t = transcriptRepository.findByRecordingId(recordingId).orElseGet(Transcript::new);
            t.setRecordingId(recordingId);
            t.setLanguage(result.language());
            t.setDurationSeconds(result.durationSeconds());
            t.setFullText(result.fullText());
            try {
                t.setSegmentsJson(objectMapper.writeValueAsString(result.segments()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize transcript segments", e);
            }
            transcriptRepository.save(t);
            markStatus(recordingId, RecordingStatus.COMPLETED, null);
            log.info("Transcription completed for {}", recordingId);
            events.publishEvent(new TranscriptionCompletedEvent(recordingId));
        } catch (Exception e) {
            log.error("Transcription failed for {}", recordingId, e);
            markStatus(recordingId, RecordingStatus.FAILED, truncate(e.getMessage(), 2000));
        }
    }

    @Transactional
    protected void markStatus(UUID recordingId, RecordingStatus status, String error) {
        recordingRepository.findById(recordingId).ifPresent(r -> {
            r.setStatus(status);
            r.setErrorMessage(error);
            recordingRepository.save(r);
        });
    }

    @Transactional(readOnly = true)
    public List<Recording> listForUser(UUID userId) {
        return recordingRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> statsForUser(UUID userId) {
        var recs = recordingRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        long completed = recs.stream().filter(r -> r.getStatus() == RecordingStatus.COMPLETED).count();
        long inProgress = recs.stream().filter(r -> r.getStatus() == RecordingStatus.TRANSCRIBING || r.getStatus() == RecordingStatus.UPLOADED).count();
        double totalSeconds = 0;
        for (Recording r : recs) {
            var t = transcriptRepository.findByRecordingId(r.getId()).orElse(null);
            if (t != null && t.getDurationSeconds() != null) totalSeconds += t.getDurationSeconds();
        }
        return java.util.Map.of(
            "total", recs.size(),
            "completed", completed,
            "inProgress", inProgress,
            "totalMinutes", (long) Math.round(totalSeconds / 60.0)
        );
    }

    @Transactional(readOnly = true)
    public Recording getForUser(UUID id, UUID userId) {
        return recordingRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new NoSuchElementException("Recording not found: " + id));
    }

    @Transactional
    public void deleteForUser(UUID id, UUID userId) {
        Recording r = recordingRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new NoSuchElementException("Recording not found: " + id));
        Path audioPath = fileStorageService.resolve(r.getAudioFilePath());

        chatMessageRepository.deleteByRecordingIdAndUserId(id, userId);
        transcriptRepository.findByRecordingId(id).ifPresent(transcriptRepository::delete);
        insightRepository.findByRecordingId(id).ifPresent(insightRepository::delete);
        meetingRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
            .filter(m -> id.equals(m.getRecordingId()))
            .forEach(m -> {
                m.setRecordingId(null);
                meetingRepository.save(m);
            });
        recordingRepository.deleteById(id);
        try {
            if (Files.exists(audioPath)) {
                Files.delete(audioPath);
                log.info("Deleted audio file {}", audioPath);
            }
        } catch (java.io.IOException e) {
            log.warn("Failed to delete audio file {}: {}", audioPath, e.getMessage());
        }
    }

    @Transactional
    public Recording retryForUser(UUID id, UUID userId) {
        Recording r = recordingRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new NoSuchElementException("Recording not found: " + id));
        if (r.getStatus() == RecordingStatus.TRANSCRIBING) {
            throw new IllegalStateException("Recording is already transcribing");
        }
        Path audioPath = fileStorageService.resolve(r.getAudioFilePath());
        if (!Files.exists(audioPath)) {
            throw new IllegalStateException("Audio file no longer on disk: " + audioPath);
        }
        r.setStatus(RecordingStatus.UPLOADED);
        r.setErrorMessage(null);
        return recordingRepository.save(r);
    }

    @Transactional(readOnly = true)
    public Optional<Transcript> getTranscriptForUser(UUID recordingId, UUID userId) {
        return recordingRepository.findByIdAndUserId(recordingId, userId)
            .flatMap(r -> transcriptRepository.findByRecordingId(r.getId()));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
