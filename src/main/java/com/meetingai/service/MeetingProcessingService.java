package com.meetingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingai.dto.MeetingAnalysisResult;
import com.meetingai.entity.Meeting;
import com.meetingai.entity.MeetingStatus;
import com.meetingai.repository.MeetingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Runs the slow part of the pipeline (Whisper transcription, then Ollama
 * summarization) on a background thread from the "meetingProcessingExecutor"
 * pool, so the HTTP request that kicked off /api/meetings/analyze can
 * return immediately instead of blocking for however long those two steps
 * take.
 *
 * This MUST be a separate bean from MeetingService. @Async only works
 * through Spring's proxy — a call from one method to another inside the
 * SAME class bypasses the proxy and just runs synchronously on the
 * caller's thread, silently defeating the whole point. Splitting the
 * "kick off the job" code (MeetingService) from "do the job" code (this
 * class) into two beans is what makes @Async actually take effect.
 */
@Service
public class MeetingProcessingService {

    private static final Logger log = LoggerFactory.getLogger(MeetingProcessingService.class);

    private final MeetingRepository meetingRepository;
    private final WhisperService whisperService;
    private final AIService aiService;
    private final ReminderService reminderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MeetingProcessingService(MeetingRepository meetingRepository,
                                     WhisperService whisperService,
                                     AIService aiService,
                                     ReminderService reminderService) {
        this.meetingRepository = meetingRepository;
        this.whisperService = whisperService;
        this.aiService = aiService;
        this.reminderService = reminderService;
    }

    @Async("meetingProcessingExecutor")
    public void processAsync(Long meetingId, String audioFilePath) {
        log.info("[Pipeline] (async) Starting background processing for meeting id={} on thread={}",
                meetingId, Thread.currentThread().getName());

        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new RuntimeException("Meeting not found: " + meetingId));

        try {
            // 1. Transcribe
            meeting.setStatus(MeetingStatus.TRANSCRIBING);
            meetingRepository.save(meeting);
            log.info("[Pipeline] id={} — status=TRANSCRIBING, calling Whisper...", meetingId);

            TranscriptionResult transcription = whisperService.transcribe(audioFilePath);
            meeting.setTranscript(transcription.getText());
            meeting.setDetectedLanguage(transcription.getLanguage());
            log.info("[Pipeline] id={} — Whisper finished. detectedLanguage='{}', transcript length={} chars",
                    meetingId, transcription.getLanguage(), transcription.getText().length());
            log.debug("[Pipeline] id={} — full transcript:\n{}", meetingId, transcription.getText());

            // 2. Summarize / extract action items
            meeting.setStatus(MeetingStatus.SUMMARIZING);
            meetingRepository.save(meeting);
            log.info("[Pipeline] id={} — status=SUMMARIZING, calling Ollama...", meetingId);

            String rawJson = aiService.analyzeMeeting(meeting);
            log.debug("[Pipeline] id={} — raw LLM output:\n{}", meetingId, rawJson);

            MeetingAnalysisResult result = parseAnalysisResult(rawJson);
            cleanResult(result);
            log.info("[Pipeline] id={} — Ollama finished. participants={}, topics={}, actionItems={}, decisions={}, openQuestions={}",
                    meetingId,
                    result.getParticipants().size(), result.getTopics().size(), result.getActionItems().size(),
                    result.getDecisions().size(), result.getOpenQuestions().size());

            meeting.setSummary(result.getSummary());
            meeting.setParticipantsJson(objectMapper.writeValueAsString(result.getParticipants()));
            meeting.setTopicsJson(objectMapper.writeValueAsString(result.getTopics()));
            meeting.setActionItemsJson(objectMapper.writeValueAsString(result.getActionItems()));
            meeting.setDecisionsJson(objectMapper.writeValueAsString(result.getDecisions()));
            meeting.setOpenQuestionsJson(objectMapper.writeValueAsString(result.getOpenQuestions()));
            meeting.setStatus(MeetingStatus.COMPLETED);
            meetingRepository.save(meeting);
            log.info("[Pipeline] id={} — status=COMPLETED. Done.", meetingId);

            // 3. Notify: email + calendar reminder via the user's connected
            // Google account. Deliberately isolated in its own try/catch —
            // the meeting analysis itself already succeeded and is saved
            // above, so a reminder failure (e.g. Google not connected, an
            // expired refresh token) must never turn this meeting FAILED.
            try {
                log.info("[Pipeline] id={} — sending reminder (email + calendar)...", meetingId);
                reminderService.sendMeetingReminders(
                        meeting.getId(),
                        meeting.getUser().getId(),
                        meeting.getTitle(),
                        result
                );
            } catch (Exception e) {
                log.error("[Pipeline] id={} — reminder step failed (meeting itself completed fine): {}",
                        meetingId, e.getMessage(), e);
            }

        } catch (Exception e) {
            log.error("[Pipeline] id={} — FAILED at some stage: {}", meetingId, e.getMessage(), e);
            meeting.setStatus(MeetingStatus.FAILED);
            meetingRepository.save(meeting);
            // Deliberately not rethrown: this runs on a background thread with
            // no caller waiting on it. The FAILED status row IS the error
            // report — there's no HTTP response left to attach an exception to.
        }
    }

    private MeetingAnalysisResult parseAnalysisResult(String rawJson) {
        try {
            String cleaned = extractJsonObject(rawJson);
            return objectMapper.readValue(cleaned, MeetingAnalysisResult.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse LLM JSON output: " + e.getMessage()
                    + "\nRaw output was:\n" + rawJson, e);
        }
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            throw new RuntimeException("No JSON object found in LLM output: " + text);
        }
        return text.substring(start, end + 1);
    }

    /**
     * Defensive cleanup: even with explicit prompt instructions, local models
     * occasionally still return placeholder entries instead of an empty
     * array. Strips those out (same logic as before — moved here since this
     * is now where the LLM result is parsed).
     */
    private void cleanResult(MeetingAnalysisResult result) {
        if (result.getParticipants() != null) {
            java.util.LinkedHashMap<String, com.meetingai.dto.Participant> deduped = new java.util.LinkedHashMap<>();
            for (com.meetingai.dto.Participant p : result.getParticipants()) {
                if (p == null || p.getName() == null || p.getName().isBlank()) {
                    continue;
                }
                String key = p.getName().trim().toLowerCase();
                deduped.putIfAbsent(key, p);
            }
            result.setParticipants(new java.util.ArrayList<>(deduped.values()));
        } else {
            result.setParticipants(Collections.emptyList());
        }

        if (result.getTopics() != null) {
            result.setTopics(result.getTopics().stream()
                    .filter(topic -> topic != null
                            && topic.getTitle() != null
                            && !topic.getTitle().isBlank())
                    .collect(java.util.stream.Collectors.toList()));
        } else {
            result.setTopics(Collections.emptyList());
        }

        if (result.getActionItems() != null) {
            result.setActionItems(result.getActionItems().stream()
                    .filter(item -> item != null
                            && item.getTask() != null
                            && !item.getTask().isBlank())
                    .collect(java.util.stream.Collectors.toList()));
        } else {
            result.setActionItems(Collections.emptyList());
        }

        result.setDecisions(cleanStringList(result.getDecisions()));
        result.setOpenQuestions(cleanStringList(result.getOpenQuestions()));
    }

    private List<String> cleanStringList(List<String> list) {
        if (list == null) return Collections.emptyList();
        return list.stream()
                .filter(s -> s != null && !s.isBlank())
                .collect(java.util.stream.Collectors.toList());
    }
}
