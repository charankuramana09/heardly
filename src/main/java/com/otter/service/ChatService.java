package com.otter.service;

import com.otter.domain.ChatMessage;
import com.otter.domain.Insight;
import com.otter.domain.Recording;
import com.otter.domain.RecordingStatus;
import com.otter.domain.Transcript;
import com.otter.repository.ChatMessageRepository;
import com.otter.repository.InsightRepository;
import com.otter.repository.RecordingRepository;
import com.otter.repository.TranscriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
        You are Heardly's transcript assistant. You answer questions about ONE specific meeting recording.

        Rules:
        - Answer ONLY based on the transcript provided below. Do not invent facts.
        - If the answer is not in the transcript, say "I don't see that in this recording."
        - Be concise: usually 1-3 sentences. Add detail only if explicitly asked.
        - Quote directly from the transcript when it helps.
        - Don't refer to "the transcript" or "the speaker said" — answer naturally and directly.

        Transcript of this recording:
        ---
        %s
        ---
        """;

    private static final int MAX_HISTORY = 20;
    private static final int MAX_TRANSCRIPT_CHARS = 80_000;

    private final ChatMessageRepository chatRepo;
    private final RecordingRepository recordingRepo;
    private final TranscriptRepository transcriptRepo;
    private final InsightRepository insightRepo;
    private final OpenAiClient openAi;

    public ChatService(
        ChatMessageRepository chatRepo,
        RecordingRepository recordingRepo,
        TranscriptRepository transcriptRepo,
        InsightRepository insightRepo,
        OpenAiClient openAi
    ) {
        this.chatRepo = chatRepo;
        this.recordingRepo = recordingRepo;
        this.transcriptRepo = transcriptRepo;
        this.insightRepo = insightRepo;
        this.openAi = openAi;
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> history(UUID recordingId, UUID userId) {
        return chatRepo.findAllByRecordingIdAndUserIdOrderByCreatedAtAsc(recordingId, userId);
    }

    @Transactional
    public ChatMessage ask(UUID recordingId, UUID userId, String question) {
        if (!openAi.isEnabled()) {
            throw new IllegalStateException("AI chat is unavailable — OpenAI API key is not configured.");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question is required");
        }

        Recording recording = recordingRepo.findByIdAndUserId(recordingId, userId)
            .orElseThrow(() -> new NoSuchElementException("Recording not found: " + recordingId));
        Transcript transcript = transcriptRepo.findByRecordingId(recordingId)
            .orElseThrow(() -> new IllegalStateException("Transcript not ready"));

        List<ChatMessage> existing = chatRepo.findAllByRecordingIdAndUserIdOrderByCreatedAtAsc(recordingId, userId);
        List<ChatMessage> recent = existing.size() > MAX_HISTORY
            ? existing.subList(existing.size() - MAX_HISTORY, existing.size())
            : existing;

        String fullText = transcript.getFullText() == null ? "" : transcript.getFullText();
        if (fullText.length() > MAX_TRANSCRIPT_CHARS) {
            fullText = fullText.substring(0, MAX_TRANSCRIPT_CHARS) + "\n[...transcript truncated]";
        }
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, fullText);

        List<LlmClient.ChatTurn> turns = new ArrayList<>();
        for (ChatMessage m : recent) {
            turns.add(new LlmClient.ChatTurn(
                m.getRole() == ChatMessage.Role.USER ? "user" : "assistant",
                m.getContent()
            ));
        }
        turns.add(new LlmClient.ChatTurn("user", question.trim()));

        ChatMessage userMsg = new ChatMessage();
        userMsg.setId(UUID.randomUUID());
        userMsg.setRecordingId(recordingId);
        userMsg.setUserId(userId);
        userMsg.setRole(ChatMessage.Role.USER);
        userMsg.setContent(question.trim());
        chatRepo.save(userMsg);

        String answer;
        try {
            answer = openAi.chat(systemPrompt, turns);
        } catch (Exception e) {
            log.error("Chat call failed for recording {}", recordingId, e);
            throw new IllegalStateException("AI chat failed: " + e.getMessage());
        }

        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setId(UUID.randomUUID());
        assistantMsg.setRecordingId(recordingId);
        assistantMsg.setUserId(userId);
        assistantMsg.setRole(ChatMessage.Role.ASSISTANT);
        assistantMsg.setContent(answer == null ? "" : answer.trim());
        assistantMsg.setModelName(openAi.modelName());
        return chatRepo.save(assistantMsg);
    }

    @Transactional
    public long clear(UUID recordingId, UUID userId) {
        recordingRepo.findByIdAndUserId(recordingId, userId)
            .orElseThrow(() -> new NoSuchElementException("Recording not found: " + recordingId));
        return chatRepo.deleteByRecordingIdAndUserId(recordingId, userId);
    }

    // ==================== GLOBAL CHAT (across all the user's recordings) ====================

    private static final String GLOBAL_SYSTEM_PROMPT_TEMPLATE = """
        You are Heardly, the user's AI assistant for ALL of their meeting recordings.
        You can answer questions across multiple recordings: "what was decided in my meetings this morning",
        "deadlines mentioned this week", "what did Alice say about pricing across the last three calls", etc.

        Rules:
        - Use ONLY the context below. Do not invent facts not present.
        - If something isn't in the context, say so.
        - When citing, refer to recordings by their title and date, e.g. "In your 2026-06-02 'Q2 planning' meeting, ..."
        - Be concise. 1-3 sentences unless the user asks for detail.
        - Today's date is %s. Use it to resolve relative phrases like "this week" or "yesterday".

        Below is structured context for each of the user's completed recordings:

        %s
        """;

    private static final int MAX_GLOBAL_CONTEXT_RECORDINGS = 60;

    @Transactional(readOnly = true)
    public List<ChatMessage> globalHistory(UUID userId) {
        return chatRepo.findAllByUserIdAndRecordingIdIsNullOrderByCreatedAtAsc(userId);
    }

    @Transactional
    public ChatMessage globalAsk(UUID userId, String question) {
        if (!openAi.isEnabled()) {
            throw new IllegalStateException("AI chat is unavailable — OpenAI API key is not configured.");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question is required");
        }

        String context = buildGlobalContext(userId);
        String systemPrompt = String.format(GLOBAL_SYSTEM_PROMPT_TEMPLATE,
            java.time.LocalDate.now(), context);

        List<ChatMessage> existing = chatRepo.findAllByUserIdAndRecordingIdIsNullOrderByCreatedAtAsc(userId);
        List<ChatMessage> recent = existing.size() > MAX_HISTORY
            ? existing.subList(existing.size() - MAX_HISTORY, existing.size())
            : existing;

        List<LlmClient.ChatTurn> turns = new ArrayList<>();
        for (ChatMessage m : recent) {
            turns.add(new LlmClient.ChatTurn(
                m.getRole() == ChatMessage.Role.USER ? "user" : "assistant",
                m.getContent()
            ));
        }
        turns.add(new LlmClient.ChatTurn("user", question.trim()));

        ChatMessage userMsg = new ChatMessage();
        userMsg.setId(UUID.randomUUID());
        userMsg.setUserId(userId);
        userMsg.setRecordingId(null);
        userMsg.setRole(ChatMessage.Role.USER);
        userMsg.setContent(question.trim());
        chatRepo.save(userMsg);

        String answer;
        try {
            answer = openAi.chat(systemPrompt, turns);
        } catch (Exception e) {
            log.error("Global chat call failed for user {}", userId, e);
            throw new IllegalStateException("AI chat failed: " + e.getMessage());
        }

        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setId(UUID.randomUUID());
        assistantMsg.setUserId(userId);
        assistantMsg.setRecordingId(null);
        assistantMsg.setRole(ChatMessage.Role.ASSISTANT);
        assistantMsg.setContent(answer == null ? "" : answer.trim());
        assistantMsg.setModelName(openAi.modelName());
        return chatRepo.save(assistantMsg);
    }

    @Transactional
    public long clearGlobal(UUID userId) {
        return chatRepo.deleteByUserIdAndRecordingIdIsNull(userId);
    }

    private String buildGlobalContext(UUID userId) {
        var recordings = recordingRepo.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
            .filter(r -> r.getStatus() == RecordingStatus.COMPLETED)
            .limit(MAX_GLOBAL_CONTEXT_RECORDINGS)
            .toList();

        if (recordings.isEmpty()) {
            return "(no completed recordings yet)";
        }

        var sb = new StringBuilder();
        int i = 1;
        for (Recording r : recordings) {
            Insight insight = insightRepo.findByRecordingId(r.getId()).orElse(null);
            Transcript t = transcriptRepo.findByRecordingId(r.getId()).orElse(null);

            sb.append("=== Recording ").append(i++).append(" ===\n");
            String title = insight != null && insight.getSmartTitle() != null
                ? insight.getSmartTitle() : r.getOriginalFilename();
            sb.append("Title: ").append(title != null ? title : "Untitled").append("\n");
            if (r.getOriginalFilename() != null && insight != null && insight.getSmartTitle() != null) {
                sb.append("Filename: ").append(r.getOriginalFilename()).append("\n");
            }
            sb.append("Date: ").append(r.getCreatedAt()).append("\n");
            if (t != null && t.getDurationSeconds() != null) {
                sb.append("Duration: ").append(Math.round(t.getDurationSeconds() / 60.0)).append(" min\n");
            }
            if (insight != null) {
                if (insight.getSummary() != null) sb.append("Summary: ").append(insight.getSummary()).append("\n");
                if (insight.getKeyTopicsJson() != null && !insight.getKeyTopicsJson().equals("[]"))
                    sb.append("Topics: ").append(insight.getKeyTopicsJson()).append("\n");
                if (insight.getDecisionsJson() != null && !insight.getDecisionsJson().equals("[]"))
                    sb.append("Decisions: ").append(insight.getDecisionsJson()).append("\n");
                if (insight.getActionItemsJson() != null && !insight.getActionItemsJson().equals("[]"))
                    sb.append("Action items: ").append(insight.getActionItemsJson()).append("\n");
            } else if (t != null && t.getFullText() != null) {
                String snippet = t.getFullText();
                if (snippet.length() > 600) snippet = snippet.substring(0, 600) + "…";
                sb.append("Transcript snippet: ").append(snippet).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
