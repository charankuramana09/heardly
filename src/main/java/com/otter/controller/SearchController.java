package com.otter.controller;

import com.otter.domain.User;
import com.otter.service.SearchService;
import com.otter.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;
    private final UserService userService;

    public SearchController(SearchService searchService, UserService userService) {
        this.searchService = searchService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<SearchService.SearchHit>> search(
        @RequestParam("q") String q,
        @AuthenticationPrincipal UserDetails principal
    ) {
        if (principal == null) return ResponseEntity.status(401).build();
        User u = userService.getByEmail(principal.getUsername());
        UUID userId = u.getId();
        return ResponseEntity.ok(searchService.search(userId, q));
    }
}
