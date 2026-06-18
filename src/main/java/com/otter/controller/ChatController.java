package com.otter.controller;

import com.otter.domain.ChatMessage;
import com.otter.domain.User;
import com.otter.service.ChatService;
import com.otter.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/recordings/{id}/chat")
public class ChatController {

    private final ChatService chatService;
    private final UserService userService;

    public ChatController(ChatService chatService, UserService userService) {
        this.chatService = chatService;
        this.userService = userService;
    }

    public record AskRequest(String question) {}
    public record MessageDto(UUID id, String role, String content, String modelName, Instant createdAt) {
        public static MessageDto from(ChatMessage m) {
            return new MessageDto(m.getId(),
                m.getRole().name().toLowerCase(),
                m.getContent(),
                m.getModelName(),
                m.getCreatedAt());
        }
    }

    @GetMapping
    public List<MessageDto> history(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        return chatService.history(id, userId).stream().map(MessageDto::from).toList();
    }

    @PostMapping
    public ResponseEntity<?> ask(@PathVariable UUID id,
                                 @RequestBody AskRequest req,
                                 @AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        var answer = chatService.ask(id, userId, req.question());
        return ResponseEntity.ok(MessageDto.from(answer));
    }

    @DeleteMapping
    public ResponseEntity<?> clear(@PathVariable UUID id, @AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        long deleted = chatService.clear(id, userId);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    private UUID currentUserId(UserDetails principal) {
        if (principal == null) throw new IllegalStateException("Not authenticated");
        User u = userService.getByEmail(principal.getUsername());
        return u.getId();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
