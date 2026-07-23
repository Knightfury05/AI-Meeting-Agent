package com.meetingai.service;

import com.meetingai.ai.OllamaClient;
import com.meetingai.dto.ChatMessageResponse;
import com.meetingai.entity.ChatMessage;
import com.meetingai.entity.Meeting;
import com.meetingai.entity.MeetingStatus;
import com.meetingai.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Answers follow-up questions about a specific meeting. Unlike AIService
 * (which does the one-shot transcript -> MOM extraction right after
 * upload), this runs on-demand per question, grounds every answer in the
 * meeting's own transcript + summary, and persists both sides of the
 * conversation so it survives a page reload.
 */
@Service
public class MeetingChatService {

    private static final Logger log = LoggerFactory.getLogger(MeetingChatService.class);

    // Same model AIService uses for the initial summary — keeps behavior
    // and output language handling consistent between the two.
    private static final String MODEL = "aya:8b";

    // How many prior turns (user + AI messages combined) to replay back to
    // the model for conversational continuity. Kept small since the full
    // transcript is already in every prompt and local models have limited
    // context windows.
    private static final int MAX_HISTORY_MESSAGES = 12;

    private final ChatMessageRepository chatMessageRepository;
    private final MeetingService meetingService;
    private final OllamaClient ollamaClient;

    public MeetingChatService(ChatMessageRepository chatMessageRepository,
                               MeetingService meetingService,
                               OllamaClient ollamaClient) {
        this.chatMessageRepository = chatMessageRepository;
        this.meetingService = meetingService;
        this.ollamaClient = ollamaClient;
    }

    /** Full chat history for a meeting, oldest first — only if it belongs to the current user. */
    public List<ChatMessageResponse> getHistory(Long meetingId) {
        // getOwnedMeetingEntity throws NoSuchElementException if this
        // meeting doesn't exist or isn't the caller's — same 404 behavior
        // as every other meeting endpoint.
        meetingService.getOwnedMeetingEntity(meetingId);
        return chatMessageRepository.findAllByMeetingIdOrderByCreatedAtAsc(meetingId).stream()
                .map(ChatMessageResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Persists the user's question, asks Ollama to answer it grounded in
     * the meeting's transcript/summary/prior turns, persists the answer,
     * and returns it.
     */
    public ChatMessageResponse ask(Long meetingId, String question) {
        Meeting meeting = meetingService.getOwnedMeetingEntity(meetingId);

        if (meeting.getStatus() != MeetingStatus.COMPLETED) {
            throw new IllegalArgumentException(
                    "This meeting hasn't finished processing yet — wait for the summary before asking questions.");
        }

        List<ChatMessage> priorHistory = chatMessageRepository.findAllByMeetingIdOrderByCreatedAtAsc(meetingId);

        ChatMessage userMessage = ChatMessage.builder()
                .meeting(meeting)
                .sender(ChatMessage.Sender.USER)
                .content(question)
                .build();
        chatMessageRepository.save(userMessage);

        String prompt = buildPrompt(meeting, priorHistory, question);
        log.info("[Chat] meetingId={} — asking Ollama, prompt length={} chars", meetingId, prompt.length());

        String answer;
        try {
            answer = ollamaClient.generate(MODEL, prompt).trim();
        } catch (Exception e) {
            log.error("[Chat] meetingId={} — Ollama call failed: {}", meetingId, e.getMessage(), e);
            throw new com.meetingai.exception.AiServiceUnavailableException(
                    "The local AI model isn't reachable right now. Make sure Ollama is running and try again.", e);
        }

        ChatMessage aiMessage = ChatMessage.builder()
                .meeting(meeting)
                .sender(ChatMessage.Sender.AI)
                .content(answer)
                .build();
        chatMessageRepository.save(aiMessage);

        return ChatMessageResponse.from(aiMessage);
    }

    private String buildPrompt(Meeting meeting, List<ChatMessage> priorHistory, String question) {
        String outputLanguage = (meeting.getOutputLanguage() != null && !meeting.getOutputLanguage().isBlank())
                ? meeting.getOutputLanguage()
                : "English";

        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant answering questions about ONE specific meeting.\n")
                .append("Only use the meeting summary and transcript below to answer — do not use outside " +
                        "knowledge or invent details that aren't supported by them. If the answer genuinely " +
                        "isn't in there, say so plainly instead of guessing.\n")
                .append("Respond in ").append(outputLanguage).append(". Keep answers concise — a few sentences, " +
                        "or a short list only if the question actually asks for multiple items.\n\n")
                .append("MEETING SUMMARY:\n")
                .append(blankToPlaceholder(meeting.getSummary()))
                .append("\n\n")
                .append("FULL TRANSCRIPT:\n")
                .append(blankToPlaceholder(meeting.getTranscript()))
                .append("\n\n");

        List<ChatMessage> recent = priorHistory.size() > MAX_HISTORY_MESSAGES
                ? priorHistory.subList(priorHistory.size() - MAX_HISTORY_MESSAGES, priorHistory.size())
                : priorHistory;

        if (!recent.isEmpty()) {
            sb.append("CONVERSATION SO FAR:\n");
            for (ChatMessage m : recent) {
                sb.append(m.getSender() == ChatMessage.Sender.USER ? "User: " : "Assistant: ")
                        .append(m.getContent())
                        .append("\n");
            }
            sb.append("\n");
        }

        sb.append("Now answer this new question:\n").append(question);
        return sb.toString();
    }

    private String blankToPlaceholder(String text) {
        return (text == null || text.isBlank()) ? "(none available)" : text;
    }
}
