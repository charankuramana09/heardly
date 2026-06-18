package com.otter.controller;

import com.otter.controller.dto.MeetingResponse;
import com.otter.domain.User;
import com.otter.service.MeetingService;
import com.otter.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    private final MeetingService meetingService;
    private final UserService userService;

    public MeetingController(MeetingService meetingService, UserService userService) {
        this.meetingService = meetingService;
        this.userService = userService;
    }

    public record CreateMeetingRequest(String meetingUrl, String botName) {}

    @PostMapping
    public ResponseEntity<MeetingResponse> schedule(
        @RequestBody CreateMeetingRequest req,
        @AuthenticationPrincipal UserDetails principal
    ) {
        UUID userId = currentUserId(principal);
        var saved = meetingService.schedule(userId, req.meetingUrl(), req.botName());
        return ResponseEntity.status(HttpStatus.CREATED).body(MeetingResponse.from(saved));
    }

    @GetMapping
    public List<MeetingResponse> list(@AuthenticationPrincipal UserDetails principal) {
        return meetingService.listForUser(currentUserId(principal))
            .stream().map(MeetingResponse::from).toList();
    }

    @GetMapping("/{id}")
    public MeetingResponse get(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) {
        return MeetingResponse.from(meetingService.getForUser(id, currentUserId(principal)));
    }

    @DeleteMapping("/{id}")
    public MeetingResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) {
        return MeetingResponse.from(meetingService.cancel(id, currentUserId(principal)));
    }

    private UUID currentUserId(UserDetails principal) {
        if (principal == null) throw new IllegalStateException("Not authenticated");
        User u = userService.getByEmail(principal.getUsername());
        return u.getId();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
