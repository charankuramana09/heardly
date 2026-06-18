package com.otter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otter.controller.dto.InsightResponse;
import com.otter.controller.dto.RecordingResponse;
import com.otter.controller.dto.TranscriptResponse;
import com.otter.domain.Insight;
import com.otter.domain.Recording;
import com.otter.domain.Transcript;
import com.otter.domain.User;
import com.otter.service.ExportService;
import com.otter.service.InsightService;
import com.otter.service.IntegrationService;
import com.otter.service.RecordingService;
import com.otter.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/recordings")
public class RecordingController {

    private final RecordingService recordingService;
    private final UserService userService;
    private final InsightService insightService;
    private final ExportService exportService;
    private final IntegrationService integrationService;
    private final ObjectMapper objectMapper;

    public RecordingController(RecordingService recordingService, UserService userService,
                               InsightService insightService, ExportService exportService,
                               IntegrationService integrationService, ObjectMapper objectMapper) {
        this.recordingService = recordingService;
        this.userService = userService;
        this.insightService = insightService;
        this.exportService = exportService;
        this.integrationService = integrationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RecordingResponse> upload(
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal UserDetails principal
    ) throws IOException {
        UUID userId = currentUserId(principal);
        var saved = recordingService.upload(file, userId);
        recordingService.transcribeAsync(saved.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(RecordingResponse.from(saved));
    }

    @GetMapping
    public List<RecordingResponse> list(@AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        return recordingService.listForUser(userId).stream().map(RecordingResponse::from).toList();
    }

    @GetMapping("/{id}")
    public RecordingResponse get(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) {
        return RecordingResponse.from(recordingService.getForUser(id, currentUserId(principal)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        recordingService.deleteForUser(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retry")
    public Object retry(@PathVariable UUID id,
                        @AuthenticationPrincipal UserDetails principal,
                        @RequestHeader(value = "Accept", required = false) String accept) {
        UUID userId = currentUserId(principal);
        var saved = recordingService.retryForUser(id, userId);
        recordingService.transcribeAsync(saved.getId());
        if (accept != null && accept.contains("text/html")) {
            return new org.springframework.web.servlet.view.RedirectView("/recordings/" + id);
        }
        return RecordingResponse.from(saved);
    }

    @GetMapping("/{id}/transcript")
    public ResponseEntity<?> transcript(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        recordingService.getForUser(id, userId);
        return recordingService.getTranscriptForUser(id, userId)
            .map(t -> ResponseEntity.ok((Object) TranscriptResponse.from(t, objectMapper)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Transcript not ready yet")));
    }

    @GetMapping("/{id}/insights")
    public ResponseEntity<?> insights(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        recordingService.getForUser(id, userId);
        return insightService.getForRecording(id)
            .map(i -> ResponseEntity.ok((Object) InsightResponse.from(i, insightService)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Insights not generated yet")));
    }

    @PostMapping("/{id}/insights/regenerate")
    public ResponseEntity<?> regenerateInsights(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal,
                                                 @RequestHeader(value = "Accept", required = false) String accept) {
        UUID userId = currentUserId(principal);
        recordingService.getForUser(id, userId);
        insightService.generateAsync(id);
        if (accept != null && accept.contains("text/html")) {
            return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "/recordings/" + id)
                .build();
        }
        return ResponseEntity.accepted().body(Map.of("status", "regenerating"));
    }

    /**
     * Share the meeting summary (Minutes of Meeting) for this recording.
     * Body: { "email": "manager@x.com", "note": "optional", "slack": true|false }.
     * Sends the AI summary + action items + decisions via email and/or the user's
     * connected Slack channel.
     */
    @PostMapping("/{id}/share")
    public ResponseEntity<?> share(@PathVariable UUID id,
                                   @RequestBody(required = false) Map<String, Object> body,
                                   @AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        Map<String, Object> req = body == null ? Map.of() : body;

        String email = req.get("email") == null ? null : String.valueOf(req.get("email")).trim();
        String note = req.get("note") == null ? null : String.valueOf(req.get("note"));
        boolean slack = req.get("slack") != null
            && Boolean.parseBoolean(String.valueOf(req.get("slack")));

        List<String> sent = new java.util.ArrayList<>();
        if (email != null && !email.isBlank()) {
            integrationService.shareByEmail(id, userId, email, note);
            sent.add("email");
        }
        if (slack) {
            integrationService.shareToConnectedSlack(id, userId);
            sent.add("Slack");
        }
        if (sent.isEmpty()) {
            throw new IllegalArgumentException("Enter a manager's email and/or enable Slack to share.");
        }
        return ResponseEntity.ok(Map.of(
            "sent", sent,
            "message", "Minutes of Meeting sent via " + String.join(" and ", sent) + "."
        ));
    }

    @GetMapping(value = "/{id}/transcript.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> transcriptText(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        recordingService.getForUser(id, userId);
        return recordingService.getTranscriptForUser(id, userId)
            .map(Transcript::getFullText)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("Transcript not ready yet"));
    }

    @GetMapping("/{id}/transcript.docx")
    public ResponseEntity<byte[]> transcriptDocx(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) throws Exception {
        UUID userId = currentUserId(principal);
        Recording rec = recordingService.getForUser(id, userId);
        Transcript t = recordingService.getTranscriptForUser(id, userId).orElse(null);
        if (t == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        Insight ins = insightService.getForRecording(id).orElse(null);
        byte[] bytes = exportService.exportDocx(rec, t, ins);
        String filename = exportService.safeFilename(displayBaseName(rec, ins), "docx");
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"")
            .body(bytes);
    }

    @GetMapping("/{id}/transcript.pdf")
    public ResponseEntity<byte[]> transcriptPdf(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) throws Exception {
        UUID userId = currentUserId(principal);
        Recording rec = recordingService.getForUser(id, userId);
        Transcript t = recordingService.getTranscriptForUser(id, userId).orElse(null);
        if (t == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        Insight ins = insightService.getForRecording(id).orElse(null);
        byte[] bytes = exportService.exportPdf(rec, t, ins);
        String filename = exportService.safeFilename(displayBaseName(rec, ins), "pdf");
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"")
            .body(bytes);
    }

    private String displayBaseName(Recording r, Insight i) {
        if (i != null && i.getSmartTitle() != null && !i.getSmartTitle().isBlank()) return i.getSmartTitle();
        if (r.getOriginalFilename() != null) {
            String n = r.getOriginalFilename();
            int dot = n.lastIndexOf('.');
            return dot > 0 ? n.substring(0, dot) : n;
        }
        return "transcript";
    }

    private UUID currentUserId(UserDetails principal) {
        if (principal == null) {
            throw new IllegalStateException("Not authenticated");
        }
        User u = userService.getByEmail(principal.getUsername());
        return u.getId();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> handleIoError(IOException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "I/O error: " + e.getMessage()));
    }
}
