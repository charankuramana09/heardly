package com.otter.controller;

import com.otter.controller.ChatController.AskRequest;
import com.otter.controller.ChatController.MessageDto;
import com.otter.domain.User;
import com.otter.service.ChatService;
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
@RequestMapping("/api/chat")
public class GlobalChatController {

    private final ChatService chatService;
    private final UserService userService;

    public GlobalChatController(ChatService chatService, UserService userService) {
        this.chatService = chatService;
        this.userService = userService;
    }

    @GetMapping
    public List<MessageDto> history(@AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        return chatService.globalHistory(userId).stream().map(MessageDto::from).toList();
    }

    @PostMapping
    public ResponseEntity<?> ask(@RequestBody AskRequest req, @AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        var answer = chatService.globalAsk(userId, req.question());
        return ResponseEntity.ok(MessageDto.from(answer));
    }

    @DeleteMapping
    public ResponseEntity<?> clear(@AuthenticationPrincipal UserDetails principal) {
        UUID userId = currentUserId(principal);
        long deleted = chatService.clearGlobal(userId);
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
