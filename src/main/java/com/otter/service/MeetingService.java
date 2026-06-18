package com.otter.service;

import com.otter.domain.Meeting;
import com.otter.domain.MeetingStatus;
import com.otter.domain.Recording;
import com.otter.domain.RecordingStatus;
import com.otter.repository.MeetingRepository;
import com.otter.repository.RecordingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class MeetingService {

    private static final Logger log = LoggerFactory.getLogger(MeetingService.class);

    private final MeetingRepository meetingRepository;
    private final RecordingRepository recordingRepository;
    private final RecallClient recallClient;
    private final RecordingService recordingService;
    private final String defaultBotName;
    private final Path audioDir;
    private final HttpClient http = HttpClient.newHttpClient();

    public MeetingService(
        MeetingRepository meetingRepository,
        RecordingRepository recordingRepository,
        RecallClient recallClient,
        RecordingService recordingService,
        @Value("${otterfree.recall.default-bot-name}") String defaultBotName,
        @Value("${otterfree.storage.audio-dir}") String audioDir
    ) {
        this.meetingRepository = meetingRepository;
        this.recordingRepository = recordingRepository;
        this.recallClient = recallClient;
        this.recordingService = recordingService;
        this.defaultBotName = defaultBotName;
        this.audioDir = Paths.get(audioDir).toAbsolutePath().normalize();
    }

    @Transactional
    public Meeting schedule(UUID userId, String meetingUrl, String botName) {
        if (meetingUrl == null || meetingUrl.isBlank()) {
            throw new IllegalArgumentException("meetingUrl is required");
        }
        if (!isSupportedMeetingUrl(meetingUrl)) {
            throw new IllegalArgumentException("Meeting URL must be a Zoom, Google Meet, or Teams link");
        }
        String effectiveBotName = (botName == null || botName.isBlank()) ? defaultBotName : botName.trim();

        Meeting m = new Meeting();
        m.setId(UUID.randomUUID());
        m.setUserId(userId);
        m.setMeetingUrl(meetingUrl.trim());
        m.setBotName(effectiveBotName);
        m.setStatus(MeetingStatus.SCHEDULED);
        Meeting saved = meetingRepository.save(m);

        try {
            Map<String, Object> resp = recallClient.createBot(saved.getMeetingUrl(), effectiveBotName);
            String botId = String.valueOf(resp.get("id"));
            saved.setRecallBotId(botId);
            saved.setStatus(MeetingStatus.JOINING);
            log.info("Scheduled Recall bot {} for meeting {}", botId, saved.getId());
        } catch (Exception e) {
            log.error("Failed to schedule Recall bot for meeting {}", saved.getId(), e);
            saved.setStatus(MeetingStatus.FAILED);
            saved.setErrorMessage("Failed to create Recall bot: " + truncate(e.getMessage(), 1800));
        }
        return meetingRepository.save(saved);
    }

    @Transactional(readOnly = true)
    public List<Meeting> listForUser(UUID userId) {
        return meetingRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Meeting getForUser(UUID id, UUID userId) {
        return meetingRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new NoSuchElementException("Meeting not found: " + id));
    }

    @Transactional
    public Meeting cancel(UUID id, UUID userId) {
        Meeting m = getForUser(id, userId);
        if (m.getRecallBotId() != null && isActive(m.getStatus())) {
            recallClient.leaveBot(m.getRecallBotId());
        }
        m.setStatus(MeetingStatus.CANCELLED);
        return meetingRepository.save(m);
    }

    @Scheduled(fixedDelayString = "${otterfree.recall.poll-interval-ms}", initialDelay = 5000)
    public void pollActiveBots() {
        List<MeetingStatus> active = List.of(
            MeetingStatus.JOINING, MeetingStatus.WAITING,
            MeetingStatus.RECORDING, MeetingStatus.ENDED
        );
        List<Meeting> meetings = meetingRepository.findAllByStatusIn(active);
        for (Meeting m : meetings) {
            try {
                pollOne(m);
            } catch (Exception e) {
                log.warn("Polling failed for meeting {} (bot {}): {}", m.getId(), m.getRecallBotId(), e.getMessage());
            }
        }
    }

    @Transactional
    protected void pollOne(Meeting m) {
        if (m.getRecallBotId() == null) return;
        Map<String, Object> resp = recallClient.getBot(m.getRecallBotId());
        String code = recallClient.extractStatusCode(resp);
        MeetingStatus next = mapRecallStatus(code);
        if (next == null) return;

        if (next == m.getStatus()) {
            return;
        }
        log.info("Meeting {} status {} → {}", m.getId(), m.getStatus(), next);
        m.setStatus(next);

        if (next == MeetingStatus.DONE) {
            String url = recallClient.extractAudioDownloadUrl(resp);
            if (url == null) {
                m.setStatus(MeetingStatus.FAILED);
                m.setErrorMessage("Recall bot finished but no audio download URL was returned.");
            } else {
                try {
                    UUID recordingId = downloadAndCreateRecording(m, url);
                    m.setRecordingId(recordingId);
                    recordingService.transcribeAsync(recordingId);
                } catch (Exception e) {
                    log.error("Failed to download recording for meeting {}", m.getId(), e);
                    m.setStatus(MeetingStatus.FAILED);
                    m.setErrorMessage("Audio download failed: " + truncate(e.getMessage(), 1800));
                }
            }
        }
        if (next == MeetingStatus.FAILED) {
            Object subCode = null;
            Object statusChanges = resp.get("status_changes");
            if (statusChanges instanceof List<?> changes && !changes.isEmpty()) {
                Object latest = changes.get(changes.size() - 1);
                if (latest instanceof Map<?, ?> ch) subCode = ch.get("sub_code");
            }
            m.setErrorMessage("Recall bot fatal" + (subCode != null ? ": " + subCode : ""));
        }
        meetingRepository.save(m);
    }

    private UUID downloadAndCreateRecording(Meeting meeting, String downloadUrl) throws Exception {
        Files.createDirectories(audioDir);
        UUID recordingId = UUID.randomUUID();
        Path target = audioDir.resolve(recordingId + ".mp3");

        HttpRequest req = HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("Recording download failed: HTTP " + resp.statusCode());
        }
        try (InputStream in = resp.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        long size = Files.size(target);
        log.info("Downloaded {} bytes for meeting {} → {}", size, meeting.getId(), target);

        Recording r = new Recording();
        r.setId(recordingId);
        r.setUserId(meeting.getUserId());
        r.setOriginalFilename(meeting.getBotName() + " - " + meeting.getId() + ".mp3");
        r.setContentType("audio/mpeg");
        r.setSizeBytes(size);
        r.setAudioFilePath(target.toString());
        r.setStatus(RecordingStatus.UPLOADED);
        recordingRepository.save(r);
        return recordingId;
    }

    private static boolean isActive(MeetingStatus s) {
        return s == MeetingStatus.JOINING || s == MeetingStatus.WAITING
            || s == MeetingStatus.RECORDING || s == MeetingStatus.ENDED;
    }

    private static boolean isSupportedMeetingUrl(String url) {
        String u = url.trim().toLowerCase();
        return u.contains("zoom.us/") || u.contains("meet.google.com/") || u.contains("teams.microsoft.com/");
    }

    private static MeetingStatus mapRecallStatus(String code) {
        if (code == null) return null;
        return switch (code) {
            case "joining_call" -> MeetingStatus.JOINING;
            case "in_waiting_room" -> MeetingStatus.WAITING;
            case "in_call_recording", "recording_permission_allowed", "in_call_not_recording" -> MeetingStatus.RECORDING;
            case "call_ended" -> MeetingStatus.ENDED;
            case "done" -> MeetingStatus.DONE;
            case "fatal", "recording_permission_denied" -> MeetingStatus.FAILED;
            default -> null;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
