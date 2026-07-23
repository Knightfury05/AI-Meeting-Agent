package com.meetingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingai.dto.*;
import com.meetingai.entity.Meeting;
import com.meetingai.entity.MeetingStatus;
import com.meetingai.entity.User;
import com.meetingai.repository.MeetingRepository;
import com.meetingai.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
public class MeetingService {

    private static final Logger log = LoggerFactory.getLogger(MeetingService.class);

    /** Extensions accepted for the "audio" upload. Whisper itself (via
    * ffmpeg) supports more than this, but we deliberately keep the
    * allowlist narrow — accepting arbitrary file types here means
    * arbitrary files get shelled out to Whisper/ffmpeg as a subprocess,
    * which is a much bigger attack surface than just rejecting odd
    * extensions up front. Add to this list if a legitimate format is
    * missing rather than removing the check.
    */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".mp3", ".wav", ".m4a", ".mp4", ".webm", ".ogg", ".flac"
    );

    private final MeetingRepository meetingRepository;
    private final MeetingProcessingService meetingProcessingService;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MeetingService(MeetingRepository meetingRepository,
                           MeetingProcessingService meetingProcessingService,
                           CurrentUserProvider currentUserProvider) {
        this.meetingRepository = meetingRepository;
        this.meetingProcessingService = meetingProcessingService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Fast path: validate the upload, save the audio file to disk, create
     * the Meeting row as PENDING, kick off background processing, and
     * return immediately. The caller gets a meeting id right away and
     * polls GET /api/meetings/{id} for progress/results — see
     * MeetingStatus for the states it will move through.
     */
    public MeetingStatusResponse startProcessing(MultipartFile audioFile, String title, String outputLanguage) {
        User currentUser = currentUserProvider.getCurrentUser();

        validateAudioFile(audioFile);

        // Normalize outputLanguage: trim, default to "English" if blank.
        String resolvedLanguage = (outputLanguage != null && !outputLanguage.isBlank())
                ? outputLanguage.trim()
                : "English";

        log.info("[Pipeline] user={} starting new meeting. title='{}', originalFilename='{}', size={} bytes, outputLanguage='{}'",
                currentUser.getEmail(), title, audioFile.getOriginalFilename(), audioFile.getSize(), resolvedLanguage);

        Meeting meeting = Meeting.builder()
                .user(currentUser)
                .title(title != null && !title.isBlank() ? title : audioFile.getOriginalFilename())
                .outputLanguage(resolvedLanguage)
                .status(MeetingStatus.PENDING)
                .build();
        meeting = meetingRepository.save(meeting);
        log.info("[Pipeline] id={} — saved initial Meeting row, status=PENDING", meeting.getId());

        String tempPath;
        try {
            tempPath = WhisperService.generateTempFilePath(audioFile.getOriginalFilename());
            audioFile.transferTo(Path.of(tempPath));
            meeting.setAudioFilePath(tempPath);
            meetingRepository.save(meeting);
            log.info("[Pipeline] id={} — audio saved to disk at: {}", meeting.getId(), tempPath);
        } catch (IOException e) {
            log.error("[Pipeline] id={} — failed to save uploaded audio: {}", meeting.getId(), e.getMessage(), e);
            meeting.setStatus(MeetingStatus.FAILED);
            meetingRepository.save(meeting);
            throw new RuntimeException("Failed to save uploaded audio file: " + e.getMessage(), e);
        }

        /**
         * Hand off to the background pool and return immediately. The async
         * method is given the meeting id + file path explicitly rather than
         * relying on SecurityContext, because the background thread does
         * NOT inherit the request thread's security context.
         */
        meetingProcessingService.processAsync(meeting.getId(), tempPath);

        return MeetingStatusResponse.builder()
                .id(meeting.getId())
                .title(meeting.getTitle())
                .status(meeting.getStatus().name())
                .build();
    }

    /** Fetch a single meeting's full result by ID — only if it belongs to the current user. */
    public MeetingResponse getResponseById(Long id) {
        Meeting meeting = getOwnedMeetingOrThrow(id);

        MeetingAnalysisResult result = new MeetingAnalysisResult();
        result.setSummary(meeting.getSummary());
        try {
            result.setParticipants(meeting.getParticipantsJson() != null
                    ? objectMapper.readValue(meeting.getParticipantsJson(), objectMapper.getTypeFactory().constructCollectionType(List.class, Participant.class))
                    : Collections.emptyList());
            result.setTopics(meeting.getTopicsJson() != null
                    ? objectMapper.readValue(meeting.getTopicsJson(), objectMapper.getTypeFactory().constructCollectionType(List.class, Topic.class))
                    : Collections.emptyList());
            result.setActionItems(meeting.getActionItemsJson() != null
                    ? objectMapper.readValue(meeting.getActionItemsJson(), objectMapper.getTypeFactory().constructCollectionType(List.class, ActionItem.class))
                    : Collections.emptyList());
            result.setDecisions(meeting.getDecisionsJson() != null
                    ? objectMapper.readValue(meeting.getDecisionsJson(), List.class)
                    : Collections.emptyList());
            result.setOpenQuestions(meeting.getOpenQuestionsJson() != null
                    ? objectMapper.readValue(meeting.getOpenQuestionsJson(), List.class)
                    : Collections.emptyList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse stored meeting JSON: " + e.getMessage(), e);
        }

        return toResponse(meeting, result);
    }

    /** All meetings belonging to the current user, most recent first. */
    public List<Meeting> getAllForCurrentUser() {
        Long userId = currentUserProvider.getCurrentUserId();
        return meetingRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Loads the raw Meeting entity (not the DTO) for the current user —
     * used by MeetingChatService, which needs the entity's transcript and
     * summary fields directly to build prompts rather than the parsed
     * MeetingResponse shape.
     */
    public Meeting getOwnedMeetingEntity(Long id) {
        return getOwnedMeetingOrThrow(id);
    }

    /**
     * Loads a meeting and verifies it belongs to the current user.
     * Returns "not found" rather than "forbidden" for meetings owned by
     * someone else, so a caller can't distinguish "doesn't exist" from
     * "exists but isn't yours" by probing IDs.
     */
    private Meeting getOwnedMeetingOrThrow(Long id) {
        Long userId = currentUserProvider.getCurrentUserId();
        return meetingRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Meeting not found: " + id));
    }

    private void validateAudioFile(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new IllegalArgumentException("Uploaded audio file is empty");
        }

        String filename = audioFile.getOriginalFilename();
        if (filename == null || !filename.contains(".")) {
            throw new IllegalArgumentException("Uploaded file must have a valid extension");
        }

        String extension = filename.substring(filename.lastIndexOf('.')).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported audio file type '" + extension + "'. Allowed types: " + ALLOWED_EXTENSIONS);
        }
    }

    private MeetingResponse toResponse(Meeting meeting, MeetingAnalysisResult result) {
        return MeetingResponse.builder()
                .id(meeting.getId())
                .title(meeting.getTitle())
                .status(meeting.getStatus().name())
                .transcript(meeting.getTranscript())
                .detectedLanguage(meeting.getDetectedLanguage())
                .outputLanguage(meeting.getOutputLanguage())
                .summary(result.getSummary())
                .participants(result.getParticipants())
                .topics(result.getTopics())
                .actionItems(result.getActionItems())
                .decisions(result.getDecisions())
                .openQuestions(result.getOpenQuestions())
                .createdAt(meeting.getCreatedAt())
                .build();
    }
}
