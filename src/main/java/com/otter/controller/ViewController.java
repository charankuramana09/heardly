package com.otter.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otter.domain.Insight;
import com.otter.domain.Recording;
import com.otter.domain.Transcript;
import com.otter.domain.User;
import com.otter.service.ChatService;
import com.otter.service.InsightService;
import com.otter.service.IntegrationService;
import com.otter.service.MeetingService;
import com.otter.service.OpenAiClient;
import com.otter.service.RecordingService;
import com.otter.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class ViewController {

    private final RecordingService recordingService;
    private final MeetingService meetingService;
    private final InsightService insightService;
    private final ChatService chatService;
    private final OpenAiClient openAiClient;
    private final UserService userService;
    private final IntegrationService integrationService;
    private final ObjectMapper objectMapper;

    public ViewController(RecordingService recordingService, MeetingService meetingService,
                          InsightService insightService, ChatService chatService,
                          OpenAiClient openAiClient, UserService userService,
                          IntegrationService integrationService, ObjectMapper objectMapper) {
        this.recordingService = recordingService;
        this.meetingService = meetingService;
        this.insightService = insightService;
        this.chatService = chatService;
        this.openAiClient = openAiClient;
        this.userService = userService;
        this.integrationService = integrationService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/")
    public String index(Model model, @AuthenticationPrincipal UserDetails principal) {
        User u = userService.getByEmail(principal.getUsername());
        model.addAttribute("currentUser", u.getEmail());
        var recs = recordingService.listForUser(u.getId());
        var meetings = meetingService.listForUser(u.getId());
        model.addAttribute("recordings", recs);
        model.addAttribute("meetings", meetings);
        model.addAttribute("stats", recordingService.statsForUser(u.getId()));
        model.addAttribute("meetingCount", meetings.size());
        model.addAttribute("activeNav", "recordings");
        return "index";
    }

    @GetMapping("/upload")
    public String uploadPage(Model model, @AuthenticationPrincipal UserDetails principal) {
        User u = userService.getByEmail(principal.getUsername());
        model.addAttribute("currentUser", u.getEmail());
        model.addAttribute("activeNav", "upload");
        return "upload";
    }

    @GetMapping("/meetings/new")
    public String schedulePage(Model model, @AuthenticationPrincipal UserDetails principal) {
        User u = userService.getByEmail(principal.getUsername());
        model.addAttribute("currentUser", u.getEmail());
        model.addAttribute("activeNav", "schedule");
        return "schedule";
    }

    @GetMapping("/integrations")
    public String integrationsPage(Model model, @AuthenticationPrincipal UserDetails principal) {
        User u = userService.getByEmail(principal.getUsername());
        model.addAttribute("currentUser", u.getEmail());
        var integrations = integrationService.mapForUser(u.getId());
        model.addAttribute("slack", integrations.get(com.otter.domain.IntegrationType.SLACK));
        model.addAttribute("gmail", integrations.get(com.otter.domain.IntegrationType.GMAIL));
        model.addAttribute("emailConfigured", integrationService.emailConfigured());
        model.addAttribute("userEmail", u.getEmail());
        model.addAttribute("activeNav", "integrations");
        return "integrations";
    }

    @GetMapping("/chat")
    public String globalChatPage(Model model, @AuthenticationPrincipal UserDetails principal) {
        User u = userService.getByEmail(principal.getUsername());
        model.addAttribute("currentUser", u.getEmail());
        model.addAttribute("chatEnabled", openAiClient.isEnabled());
        model.addAttribute("globalChatHistory",
            openAiClient.isEnabled() ? chatService.globalHistory(u.getId()) : List.of());
        model.addAttribute("activeNav", "chat");
        return "global-chat";
    }

    @GetMapping("/recordings/{id}")
    public String view(@PathVariable UUID id, Model model, @AuthenticationPrincipal UserDetails principal) {
        User u = userService.getByEmail(principal.getUsername());
        Recording recording = recordingService.getForUser(id, u.getId());
        model.addAttribute("currentUser", u.getEmail());
        model.addAttribute("recording", recording);
        Transcript transcript = recordingService.getTranscriptForUser(id, u.getId()).orElse(null);
        model.addAttribute("transcript", transcript);
        model.addAttribute("segments", buildSegments(transcript));

        Insight insight = insightService.getForRecording(id).orElse(null);
        model.addAttribute("insight", insight);
        model.addAttribute("keyTopics", insight == null ? List.of() : insightService.parseStringList(insight.getKeyTopicsJson()));
        model.addAttribute("decisions", insight == null ? List.of() : insightService.parseStringList(insight.getDecisionsJson()));
        model.addAttribute("actionItems", insight == null ? List.of() : insightService.parseActionItems(insight.getActionItemsJson()));

        model.addAttribute("chatEnabled", openAiClient.isEnabled());
        model.addAttribute("chatHistory", chatService.history(id, u.getId()));

        // Sharing the Minutes of Meeting (summary) with a manager.
        model.addAttribute("emailConfigured", integrationService.emailConfigured());
        model.addAttribute("slackConnected", integrationService.isSlackConnected(u.getId()));

        model.addAttribute("activeNav", "recordings");
        return "transcript";
    }

    private List<SegmentView> buildSegments(Transcript transcript) {
        if (transcript == null || transcript.getSegmentsJson() == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(
                transcript.getSegmentsJson(),
                new TypeReference<List<Map<String, Object>>>() {}
            );
            return raw.stream().map(SegmentView::from).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public static class SegmentView {
        private final String startFormatted;
        private final String endFormatted;
        private final String text;

        SegmentView(String startFormatted, String endFormatted, String text) {
            this.startFormatted = startFormatted;
            this.endFormatted = endFormatted;
            this.text = text;
        }

        public static SegmentView from(Map<String, Object> raw) {
            double start = toDouble(raw.get("start"));
            double end = toDouble(raw.get("end"));
            String text = String.valueOf(raw.getOrDefault("text", ""));
            return new SegmentView(formatTime(start), formatTime(end), text);
        }

        private static double toDouble(Object o) {
            return o instanceof Number n ? n.doubleValue() : 0d;
        }

        private static String formatTime(double seconds) {
            int total = (int) Math.floor(seconds);
            int h = total / 3600;
            int m = (total % 3600) / 60;
            int s = total % 60;
            return h > 0
                ? String.format("%d:%02d:%02d", h, m, s)
                : String.format("%02d:%02d", m, s);
        }

        public String getStartFormatted() { return startFormatted; }
        public String getEndFormatted() { return endFormatted; }
        public String getText() { return text; }
    }
}
