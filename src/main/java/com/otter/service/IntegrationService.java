package com.otter.service;

import com.otter.domain.Insight;
import com.otter.domain.InsightStatus;
import com.otter.domain.Integration;
import com.otter.domain.IntegrationType;
import com.otter.domain.Recording;
import com.otter.repository.InsightRepository;
import com.otter.repository.IntegrationRepository;
import com.otter.repository.RecordingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-user outbound integrations (Slack, Gmail) and dispatches the
 * AI meeting summary to whichever ones the user has connected.
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

    private final IntegrationRepository integrationRepository;
    private final RecordingRepository recordingRepository;
    private final InsightRepository insightRepository;
    private final InsightService insightService;
    private final SlackClient slackClient;
    private final EmailClient emailClient;
    private final String baseUrl;

    public IntegrationService(
        IntegrationRepository integrationRepository,
        RecordingRepository recordingRepository,
        InsightRepository insightRepository,
        InsightService insightService,
        SlackClient slackClient,
        EmailClient emailClient,
        @Value("${otterfree.base-url:http://localhost:8080}") String baseUrl
    ) {
        this.integrationRepository = integrationRepository;
        this.recordingRepository = recordingRepository;
        this.insightRepository = insightRepository;
        this.insightService = insightService;
        this.slackClient = slackClient;
        this.emailClient = emailClient;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    // ---- Connection management -------------------------------------------

    @Transactional(readOnly = true)
    public Map<IntegrationType, Integration> mapForUser(UUID userId) {
        Map<IntegrationType, Integration> map = new HashMap<>();
        for (Integration i : integrationRepository.findAllByUserId(userId)) {
            map.put(i.getType(), i);
        }
        return map;
    }

    @Transactional
    public Integration connect(UUID userId, IntegrationType type, String target) {
        String value = target == null ? "" : target.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("A connection value is required");
        }
        if (type == IntegrationType.SLACK && !slackClient.looksLikeWebhook(value)) {
            throw new IllegalArgumentException(
                "That doesn't look like a Slack Incoming Webhook URL (https://hooks.slack.com/...)");
        }
        if (type == IntegrationType.GMAIL && !value.contains("@")) {
            throw new IllegalArgumentException("Please enter a valid email address");
        }
        Integration integration = integrationRepository.findByUserIdAndType(userId, type)
            .orElseGet(() -> {
                Integration i = new Integration();
                i.setId(UUID.randomUUID());
                i.setUserId(userId);
                i.setType(type);
                return i;
            });
        integration.setTarget(value);
        integration.setEnabled(true);
        Integration saved = integrationRepository.save(integration);
        log.info("Connected {} integration for user {}", type, userId);
        return saved;
    }

    @Transactional
    public void disconnect(UUID userId, IntegrationType type) {
        integrationRepository.findByUserIdAndType(userId, type)
            .ifPresent(integrationRepository::delete);
        log.info("Disconnected {} integration for user {}", type, userId);
    }

    public boolean emailConfigured() {
        return emailClient.isEnabled();
    }

    @Transactional(readOnly = true)
    public boolean isSlackConnected(UUID userId) {
        return integrationRepository.findByUserIdAndType(userId, IntegrationType.SLACK).isPresent();
    }

    /** Sends a quick "it works" message to verify a connection. */
    public void sendTest(UUID userId, IntegrationType type) throws Exception {
        Integration integration = integrationRepository.findByUserIdAndType(userId, type)
            .orElseThrow(() -> new IllegalStateException(type + " is not connected"));
        if (type == IntegrationType.SLACK) {
            slackClient.send(integration.getTarget(),
                ":wave: *Heardly is connected.* Meeting summaries will be posted here automatically.");
        } else {
            emailClient.send(integration.getTarget(), "Heardly is connected",
                "<p>Heardly is connected. Meeting summaries will be emailed here automatically once a recording finishes transcribing.</p>");
        }
    }

    // ---- Manual share (e.g. send the MOM to a manager) --------------------

    /** Emails the meeting summary (MOM) for one recording to an arbitrary recipient. */
    @Transactional(readOnly = true)
    public void shareByEmail(UUID recordingId, UUID userId, String recipient, String note) {
        if (recipient == null || !recipient.contains("@")) {
            throw new IllegalArgumentException("A valid recipient email address is required");
        }
        if (!emailClient.isEnabled()) {
            throw new IllegalStateException(
                "Email is not configured on the server. Set MAIL_HOST / MAIL_USERNAME / MAIL_PASSWORD to enable it.");
        }
        SummaryContext ctx = loadOwnedSummary(recordingId, userId);
        try {
            emailClient.send(recipient.trim(), "Minutes of Meeting — " + ctx.title(),
                buildEmailHtml(ctx.title(), ctx.insight(), ctx.link(), note));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to send email: " + e.getMessage());
        }
    }

    /** Posts the meeting summary (MOM) for one recording to the user's connected Slack channel. */
    @Transactional(readOnly = true)
    public void shareToConnectedSlack(UUID recordingId, UUID userId) {
        SummaryContext ctx = loadOwnedSummary(recordingId, userId);
        Integration slack = integrationRepository.findByUserIdAndType(userId, IntegrationType.SLACK)
            .orElseThrow(() -> new IllegalStateException(
                "Slack isn't connected. Connect it on the Integrations page first."));
        slackClient.send(slack.getTarget(), buildSlackMessage(ctx.title(), ctx.insight(), ctx.link()));
    }

    private record SummaryContext(String title, Insight insight, String link) {}

    /** Loads a recording's completed insight, enforcing ownership. */
    private SummaryContext loadOwnedSummary(UUID recordingId, UUID userId) {
        Recording recording = recordingRepository.findById(recordingId)
            .orElseThrow(() -> new IllegalArgumentException("Recording not found"));
        if (userId != null && !userId.equals(recording.getUserId())) {
            throw new IllegalStateException("You don't have access to this recording");
        }
        Insight insight = insightRepository.findByRecordingId(recordingId)
            .orElseThrow(() -> new IllegalStateException("No AI summary exists for this recording yet"));
        if (insight.getStatus() != InsightStatus.COMPLETED) {
            throw new IllegalStateException("The AI summary isn't ready yet");
        }
        String title = insight.getSmartTitle() != null && !insight.getSmartTitle().isBlank()
            ? insight.getSmartTitle()
            : (recording.getOriginalFilename() != null ? recording.getOriginalFilename() : "Your meeting");
        return new SummaryContext(title, insight, baseUrl + "/recordings/" + recordingId);
    }

    // ---- Summary dispatch -------------------------------------------------

    /**
     * Called after an insight is generated. Sends the summary to every connected
     * integration for the recording's owner. Failures are logged, never thrown,
     * so notifications can never break transcription / insight generation.
     */
    @Async
    @Transactional(readOnly = true)
    public void dispatchSummary(UUID recordingId) {
        try {
            Recording recording = recordingRepository.findById(recordingId).orElse(null);
            if (recording == null || recording.getUserId() == null) return;
            Insight insight = insightRepository.findByRecordingId(recordingId).orElse(null);
            if (insight == null || insight.getStatus() != InsightStatus.COMPLETED) return;

            List<Integration> integrations = integrationRepository.findAllByUserId(recording.getUserId());
            if (integrations.isEmpty()) return;

            String title = insight.getSmartTitle() != null && !insight.getSmartTitle().isBlank()
                ? insight.getSmartTitle()
                : (recording.getOriginalFilename() != null ? recording.getOriginalFilename() : "Your meeting");
            String link = baseUrl + "/recordings/" + recordingId;

            for (Integration integration : integrations) {
                if (!integration.isEnabled()) continue;
                try {
                    if (integration.getType() == IntegrationType.SLACK) {
                        slackClient.send(integration.getTarget(), buildSlackMessage(title, insight, link));
                    } else if (integration.getType() == IntegrationType.GMAIL) {
                        emailClient.send(integration.getTarget(),
                            "Summary: " + title, buildEmailHtml(title, insight, link, null));
                    }
                } catch (Exception e) {
                    log.warn("Failed to send {} summary for recording {}: {}",
                        integration.getType(), recordingId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("dispatchSummary failed for recording {}", recordingId, e);
        }
    }

    // ---- Message rendering ------------------------------------------------

    private String buildSlackMessage(String title, Insight insight, String link) {
        StringBuilder sb = new StringBuilder();
        sb.append(":memo: *").append(title).append("*\n\n");
        if (insight.getSummary() != null && !insight.getSummary().isBlank()) {
            sb.append(insight.getSummary()).append("\n");
        }

        List<Map<String, Object>> actions = insightService.parseActionItems(insight.getActionItemsJson());
        if (!actions.isEmpty()) {
            sb.append("\n*Action items:*\n");
            for (Map<String, Object> a : actions) {
                sb.append("• ").append(actionItemLine(a)).append("\n");
            }
        }

        List<String> decisions = insightService.parseStringList(insight.getDecisionsJson());
        if (!decisions.isEmpty()) {
            sb.append("\n*Decisions:*\n");
            for (String d : decisions) {
                sb.append("• ").append(d).append("\n");
            }
        }

        sb.append("\n<").append(link).append("|View full transcript on Heardly>");
        return sb.toString();
    }

    private String buildEmailHtml(String title, Insight insight, String link, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:640px;margin:0 auto;color:#1f2937\">");
        sb.append("<h2 style=\"margin:0 0 4px\">").append(escape(title)).append("</h2>");
        sb.append("<p style=\"color:#6b7280;margin:0 0 16px;font-size:13px\">Meeting summary from Heardly</p>");

        if (note != null && !note.isBlank()) {
            sb.append("<div style=\"background:#f3f4f6;border-left:3px solid #4f46e5;padding:10px 14px;margin:0 0 16px;border-radius:4px\">")
              .append(escape(note).replace("\n", "<br>"))
              .append("</div>");
        }

        if (insight.getSummary() != null && !insight.getSummary().isBlank()) {
            sb.append("<p style=\"line-height:1.6\">").append(escape(insight.getSummary())).append("</p>");
        }

        List<Map<String, Object>> actions = insightService.parseActionItems(insight.getActionItemsJson());
        if (!actions.isEmpty()) {
            sb.append("<h3 style=\"margin:20px 0 8px\">Action items</h3><ul>");
            for (Map<String, Object> a : actions) {
                sb.append("<li>").append(escape(actionItemLine(a))).append("</li>");
            }
            sb.append("</ul>");
        }

        List<String> decisions = insightService.parseStringList(insight.getDecisionsJson());
        if (!decisions.isEmpty()) {
            sb.append("<h3 style=\"margin:20px 0 8px\">Decisions</h3><ul>");
            for (String d : decisions) {
                sb.append("<li>").append(escape(d)).append("</li>");
            }
            sb.append("</ul>");
        }

        List<String> topics = insightService.parseStringList(insight.getKeyTopicsJson());
        if (!topics.isEmpty()) {
            sb.append("<h3 style=\"margin:20px 0 8px\">Key topics</h3><p>")
              .append(escape(String.join(" · ", topics))).append("</p>");
        }

        sb.append("<p style=\"margin-top:24px\"><a href=\"").append(link)
          .append("\" style=\"background:#4f46e5;color:#fff;padding:10px 18px;border-radius:6px;text-decoration:none\">View full transcript</a></p>");
        sb.append("</div>");
        return sb.toString();
    }

    private String actionItemLine(Map<String, Object> a) {
        String task = String.valueOf(a.getOrDefault("task", "")).trim();
        Object owner = a.get("owner");
        Object due = a.get("due");
        StringBuilder line = new StringBuilder(task);
        if (owner != null && !"null".equals(String.valueOf(owner)) && !String.valueOf(owner).isBlank()) {
            line.append(" — ").append(owner);
        }
        if (due != null && !"null".equals(String.valueOf(due)) && !String.valueOf(due).isBlank()) {
            line.append(" (").append(due).append(")");
        }
        return line.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
