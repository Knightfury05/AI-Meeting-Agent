package com.meetingai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingai.ai.OllamaClient;
import com.meetingai.dto.MeetingReplyRequest;
import com.meetingai.dto.MeetingReplyResponse;
import com.meetingai.entity.Meeting;
import com.meetingai.entity.MeetingStatus;
import com.meetingai.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Handles the "Reply to client" action from the meeting workspace UI.
 *
 * Takes a manually-entered recipient, asks the AI to write a client-facing
 * email (subject + body) grounded in the meeting's own transcript and
 * summary, and sends it through the current user's connected Gmail account.
 *
 * Runs synchronously on the request thread because GmailService needs the
 * current request's SecurityContext to resolve the user's Google OAuth
 * credential.
 */
@Service
public class MeetingReplyService {

    private static final Logger log = LoggerFactory.getLogger(MeetingReplyService.class);

    // Same model used by AIService and MeetingChatService.
    private static final String MODEL = "aya:8b";

    private static final String DEFAULT_SUBJECT_PREFIX = "Update from meeting: ";

    private final MeetingService meetingService;
    private final CurrentUserProvider currentUserProvider;
    private final OllamaClient ollamaClient;
    private final GmailService gmailService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MeetingReplyService(MeetingService meetingService,
                               CurrentUserProvider currentUserProvider,
                               OllamaClient ollamaClient,
                               GmailService gmailService) {
        this.meetingService = meetingService;
        this.currentUserProvider = currentUserProvider;
        this.ollamaClient = ollamaClient;
        this.gmailService = gmailService;
    }

    /**
     * Generates the client email with AI and sends it via the user's Gmail.
     */
    public MeetingReplyResponse reply(Long meetingId, MeetingReplyRequest request) {
        Meeting meeting = meetingService.getOwnedMeetingEntity(meetingId);
        requireCompleted(meeting);

        String recipientName = normalizedRecipientName(request);
        String recipientEmail = request.getRecipientEmail().trim();
        String senderName = currentUserProvider.getCurrentUser().getName();

        // Prefer the reviewed draft when the client sent one (subject + body
        // from the draft step); otherwise generate a fresh email.
        GeneratedEmail draft = resolveEmail(meeting, request, recipientName, senderName);

        // ── Step 2: Send through the user's connected Gmail ───────────────
        try {
            String gmailMessageId = gmailService.sendEmail(recipientEmail, draft.subject(), draft.body()).getId();
            log.info("[Reply] meetingId={} — email sent to {}", meetingId, recipientEmail);

            return MeetingReplyResponse.builder()
                    .recipientName(recipientName)
                    .recipientEmail(recipientEmail)
                    .subject(draft.subject())
                    .body(draft.body())
                    .gmailMessageId(gmailMessageId)
                    .build();
        } catch (Exception e) {
            log.error("[Reply] meetingId={} — email send failed for {}: {}",
                    meetingId, recipientEmail, e.getMessage(), e);
            throw new IllegalArgumentException("Failed to send email: " + e.getMessage());
        }
    }

    /**
     * Drafts the client email with AI but does NOT send it, so the UI can
     * show the subject + body for review before the user confirms.
     */
    public MeetingReplyResponse draft(Long meetingId, MeetingReplyRequest request) {
        Meeting meeting = meetingService.getOwnedMeetingEntity(meetingId);
        requireCompleted(meeting);

        String recipientName = normalizedRecipientName(request);
        String recipientEmail = request.getRecipientEmail().trim();
        String senderName = currentUserProvider.getCurrentUser().getName();

        GeneratedEmail draft = generateDraft(meeting, recipientName, senderName);

        return MeetingReplyResponse.builder()
                .recipientName(recipientName)
                .recipientEmail(recipientEmail)
                .subject(draft.subject())
                .body(draft.body())
                .build();
    }

    private void requireCompleted(Meeting meeting) {
        if (meeting.getStatus() != MeetingStatus.COMPLETED) {
            throw new IllegalArgumentException(
                    "This meeting hasn't finished processing yet — wait for the summary before sending a reply.");
        }
    }

    private String normalizedRecipientName(MeetingReplyRequest request) {
        return (request.getRecipientName() != null && !request.getRecipientName().isBlank())
                ? request.getRecipientName().trim()
                : null;
    }

    private GeneratedEmail resolveEmail(Meeting meeting, MeetingReplyRequest request,
                                        String recipientName, String senderName) {
        boolean hasReviewedDraft = request.getSubject() != null && !request.getSubject().isBlank()
                && request.getBody() != null && !request.getBody().isBlank();
        if (hasReviewedDraft) {
            return new GeneratedEmail(request.getSubject().trim(), request.getBody().trim());
        }
        return generateDraft(meeting, recipientName, senderName);
    }

    // ── AI generation ─────────────────────────────────────────────────────

    private GeneratedEmail generateDraft(Meeting meeting, String recipientName, String senderName) {
        // ── Step 1: AI writes the email ───────────────────────────────────
        String prompt = buildPrompt(meeting, recipientName, senderName);
        log.info("[Reply] meetingId={} — asking Ollama for client email, prompt length={} chars",
                meeting.getId(), prompt.length());

        String raw;
        try {
            raw = ollamaClient.generate(MODEL, prompt);
        } catch (Exception e) {
            log.error("[Reply] meetingId={} — Ollama call failed: {}", meeting.getId(), e.getMessage(), e);
            throw new com.meetingai.exception.AiServiceUnavailableException(
                    "The local AI model isn't reachable right now. Make sure Ollama is running and try again.", e);
        }

        return parseGeneratedEmail(raw, meeting.getTitle());
    }

    // ── Prompt building ───────────────────────────────────────────────────

    private String buildPrompt(Meeting meeting, String recipientName, String senderName) {
        String outputLanguage = (meeting.getOutputLanguage() != null && !meeting.getOutputLanguage().isBlank())
                ? meeting.getOutputLanguage()
                : "English";

        String recipient = recipientName != null ? recipientName : "the client";

        StringBuilder sb = new StringBuilder();
        sb.append("You are a professional assistant writing an email on behalf of ")
                .append(senderName)
                .append(" to a client about an internal meeting.\n\n")
                .append("Recipient: ").append(recipient).append("\n")
                .append("Sender: ").append(senderName).append("\n")
                .append("Meeting title: ").append(safeTitle(meeting.getTitle())).append("\n\n")
                .append("Write a concise, professional client-facing email based ONLY on the meeting summary ")
                .append("and transcript below. Do not invent details that are not supported by them.\n\n")
                .append("The email must:\n")
                .append("- Open with a polite greeting addressed to ").append(recipient).append(".\n")
                .append("- Briefly state the purpose (e.g. sharing the outcomes or update from the meeting).\n")
                .append("- Summarize the key points, decisions, and any action items relevant to the client ")
                .append("in 2-4 short paragraphs.\n")
                .append("- End with a polite closing and sign off as ").append(senderName).append(".\n\n")
                .append("Respond entirely in ").append(outputLanguage).append(".\n\n")
                .append("Return ONLY valid JSON in this exact format, with no extra text before or after it:\n")
                .append("{\"subject\": \"short clear subject line\", \"body\": \"full email body\"}\n\n")
                .append("MEETING SUMMARY:\n")
                .append(blankToPlaceholder(meeting.getSummary())).append("\n\n")
                .append("FULL TRANSCRIPT:\n")
                .append(blankToPlaceholder(meeting.getTranscript())).append("\n");

        return sb.toString();
    }

    // ── Parsing ───────────────────────────────────────────────────────────

    /**
     * Local models aren't schema-constrained, so the response may come back
     * wrapped in markdown fences or with stray text. If the JSON can't be
     * parsed, fall back to a default subject and use the whole output as the
     * body rather than failing the request.
     */
    private GeneratedEmail parseGeneratedEmail(String raw, String meetingTitle) {
        String cleaned = stripCodeFences(raw);

        try {
            JsonNode root = objectMapper.readTree(cleaned);
            if (root != null && root.isObject() && root.hasNonNull("subject") && root.hasNonNull("body")) {
                return new GeneratedEmail(root.get("subject").asText().trim(), root.get("body").asText().trim());
            }
        } catch (Exception e) {
            log.warn("[Reply] Could not parse AI email JSON, falling back to plain text body: {}", e.getMessage());
        }

        return new GeneratedEmail(DEFAULT_SUBJECT_PREFIX + safeTitle(meetingTitle), cleaned);
    }

    private String stripCodeFences(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "");
            s = s.replaceFirst("\\s*```$", "");
            s = s.trim();
        }
        return s;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String safeTitle(String title) {
        return (title != null && !title.isBlank()) ? title : "Untitled meeting";
    }

    private String blankToPlaceholder(String text) {
        return (text == null || text.isBlank()) ? "(none available)" : text;
    }

    private record GeneratedEmail(String subject, String body) {
    }
}
