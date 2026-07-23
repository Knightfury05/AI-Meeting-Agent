package com.meetingai.controller;

import com.meetingai.dto.AssignWorkRequest;
import com.meetingai.dto.AssignWorkResponse;
import com.meetingai.dto.ChatMessageResponse;
import com.meetingai.dto.ChatRequest;
import com.meetingai.dto.MeetingResponse;
import com.meetingai.dto.MeetingStatusResponse;
import com.meetingai.entity.Meeting;
import com.meetingai.service.MeetingChatService;
import com.meetingai.service.MeetingService;
import com.meetingai.service.WorkAssignmentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

/** No @CrossOrigin here — CORS is configured centrally in SecurityConfig
* (app.cors.allowed-origins), since per-controller @CrossOrigin annotations
* and Spring Security's CORS filter can conflict, and a single source of
* truth for allowed origins is easier to audit.
*/
@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    private static final Logger log = LoggerFactory.getLogger(MeetingController.class);

    private final MeetingService meetingService;
    private final MeetingChatService meetingChatService;
    private final WorkAssignmentService workAssignmentService;

    public MeetingController(MeetingService meetingService, MeetingChatService meetingChatService,
                              WorkAssignmentService workAssignmentService) {
        this.meetingService = meetingService;
        this.meetingChatService = meetingChatService;
        this.workAssignmentService = workAssignmentService;
    }

    /**
     * Upload an audio file and kick off the pipeline (transcribe -> summarize
     * -> persist) in the background. Returns immediately with the new
     * meeting's id and PENDING status — it does NOT wait for processing to
     * finish. Poll GET /api/meetings/{id} until status is COMPLETED or FAILED.
     *
     * Example: curl -X POST http://localhost:8081/api/meetings/analyze \
     *            -H "Authorization: Bearer <token>" \
     *            -F "audio=@/path/to/meeting.mp3" -F "title=Sprint Planning"
     */
    @PostMapping("/analyze")
    public ResponseEntity<MeetingStatusResponse> analyzeMeeting(
            @RequestParam("audio") MultipartFile audioFile,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "outputLanguage", required = false, defaultValue = "English") String outputLanguage) {

        log.info("=== /api/meetings/analyze called ===");
        log.info("Received file: name='{}', originalFilename='{}', size={} bytes, contentType='{}'",
                audioFile.getName(), audioFile.getOriginalFilename(), audioFile.getSize(), audioFile.getContentType());
        log.info("Received title param: '{}', outputLanguage param: '{}'", title, outputLanguage);

        MeetingStatusResponse response = meetingService.startProcessing(audioFile, title, outputLanguage);
        log.info("=== /api/meetings/analyze accepted, meeting id={}, status={} ===",
                response.getId(), response.getStatus());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Fetch a single meeting's full result by ID — only returns the meeting
     * if it belongs to the currently authenticated user. While processing is
     * still in progress, this returns the same shape with status
     * PENDING/TRANSCRIBING/SUMMARIZING and empty result fields; poll again
     * until status is COMPLETED or FAILED.
     */
    @GetMapping("/{id}")
    public ResponseEntity<MeetingResponse> getMeeting(@PathVariable Long id) {
        log.info("=== GET /api/meetings/{} called ===", id);
        return ResponseEntity.ok(meetingService.getResponseById(id));
    }

    /** List the current user's meetings, most recent first (summary list for a dashboard view). */
    @GetMapping
    public ResponseEntity<List<MeetingSummaryView>> listMeetings() {
        log.info("=== GET /api/meetings (list) called ===");
        List<MeetingSummaryView> meetings = meetingService.getAllForCurrentUser().stream()
                .map(MeetingSummaryView::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(meetings);
    }

    /** Lightweight view for list screens — avoids sending full transcripts every time. */
    public record MeetingSummaryView(Long id, String title, String status, String outputLanguage, String createdAt) {
        static MeetingSummaryView from(Meeting m) {
            return new MeetingSummaryView(
                    m.getId(),
                    m.getTitle(),
                    m.getStatus().name(),
                    m.getOutputLanguage(),
                    m.getCreatedAt() != null ? m.getCreatedAt().toString() : null
            );
        }
    }

    /**
     * Full Q&A history for a meeting, oldest first — only if it belongs
     * to the current user.
     */
    @GetMapping("/{id}/chat")
    public ResponseEntity<List<ChatMessageResponse>> getChatHistory(@PathVariable Long id) {
        log.info("=== GET /api/meetings/{}/chat called ===", id);
        return ResponseEntity.ok(meetingChatService.getHistory(id));
    }

    /**
     * Ask a question about a meeting. Grounded in that meeting's own
     * transcript + summary (see MeetingChatService) — the question and
     * the AI's answer are both persisted, so history survives a reload.
     * Only works once the meeting has finished processing (status COMPLETED).
     */
    @PostMapping("/{id}/chat")
    public ResponseEntity<ChatMessageResponse> askQuestion(@PathVariable Long id, @Valid @RequestBody ChatRequest request) {
        log.info("=== POST /api/meetings/{}/chat called ===", id);
        ChatMessageResponse response = meetingChatService.ask(id, request.getMessage());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMeeting(@PathVariable Long id) {
        log.info("=== DELETE /api/meetings/{} called ===", id);
        meetingService.deleteMeeting(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sends one email per person in the request body — each containing
     * only their own task from this meeting. Powers the "Assign Work"
     * button in the workspace UI. Sends through the current user's own
     * connected Gmail account (same mechanism as GmailController), so
     * Google must already be connected — a per-recipient error is
     * reported in the response rather than failing the whole call, so a
     * bad email address for one person doesn't block the rest.
     *
     * POST /api/meetings/{id}/assign-work
     * {
     *   "assignments": [
     *     {"name": "Priya", "email": "priya@example.com", "task": "Update the API docs", "deadline": "Friday"}
     *   ]
     * }
     */
    @PostMapping("/{id}/assign-work")
    public ResponseEntity<AssignWorkResponse> assignWork(@PathVariable Long id, @RequestBody AssignWorkRequest request) {
        log.info("=== POST /api/meetings/{}/assign-work called, {} assignment(s) ===",
                id, request.getAssignments() != null ? request.getAssignments().size() : 0);
        return ResponseEntity.ok(workAssignmentService.assignWork(id, request));
    }
}
