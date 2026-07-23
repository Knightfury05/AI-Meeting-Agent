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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@Tag(name = "Meetings", description = "Meeting upload, processing, chat and work assignment")
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
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload audio for analysis", description = "Uploads an audio file and kicks off async transcription + summarization. Returns immediately with PENDING status — poll GET /api/meetings/{id} for the result.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Audio accepted, processing started",
            content = @Content(schema = @Schema(implementation = MeetingStatusResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid file type or empty file")
    })
    public ResponseEntity<MeetingStatusResponse> analyzeMeeting(
            @Parameter(description = "Audio file to transcribe (mp3, wav, m4a, mp4, webm, ogg, flac)")
            @RequestParam("audio") MultipartFile audioFile,
            @Parameter(description = "Optional meeting title")
            @RequestParam(value = "title", required = false) String title,
            @Parameter(description = "Output language for summary")
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
    @Operation(summary = "Get meeting by ID", description = "Returns the full meeting result (transcript, summary, participants, etc.). Only returns if it belongs to the authenticated user.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Meeting found",
            content = @Content(schema = @Schema(implementation = MeetingResponse.class))),
        @ApiResponse(responseCode = "404", description = "Meeting not found")
    })
    public ResponseEntity<MeetingResponse> getMeeting(@PathVariable Long id) {
        log.info("=== GET /api/meetings/{} called ===", id);
        return ResponseEntity.ok(meetingService.getResponseById(id));
    }

    /** List the current user's meetings, most recent first (summary list for a dashboard view). */
    @GetMapping
    @Operation(summary = "List user's meetings", description = "Returns a summary list of all meetings for the authenticated user, ordered by most recent first.")
    @ApiResponse(responseCode = "200", description = "List of meetings")
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
    @Operation(summary = "Get chat history", description = "Returns the full Q&A chat history for a meeting, oldest first.")
    @ApiResponse(responseCode = "200", description = "Chat history retrieved")
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
    @Operation(summary = "Ask a question about a meeting", description = "Sends a question grounded in the meeting's transcript/summary. Both the question and AI answer are persisted.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Answer generated",
            content = @Content(schema = @Schema(implementation = ChatMessageResponse.class))),
        @ApiResponse(responseCode = "400", description = "Meeting not yet processed or invalid message")
    })
    public ResponseEntity<ChatMessageResponse> askQuestion(@PathVariable Long id, @Valid @RequestBody ChatRequest request) {
        log.info("=== POST /api/meetings/{}/chat called ===", id);
        ChatMessageResponse response = meetingChatService.ask(id, request.getMessage());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a meeting", description = "Permanently deletes a meeting, its audio file, and chat history. Only allowed if it belongs to the authenticated user.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Meeting deleted"),
        @ApiResponse(responseCode = "404", description = "Meeting not found")
    })
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
    @Operation(summary = "Assign work items", description = "Sends one email per assignment via the user's connected Gmail account. Optionally creates Google Calendar events.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Work assignment(s) processed",
            content = @Content(schema = @Schema(implementation = AssignWorkResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request or Google not connected")
    })
    public ResponseEntity<AssignWorkResponse> assignWork(@PathVariable Long id, @RequestBody AssignWorkRequest request) {
        log.info("=== POST /api/meetings/{}/assign-work called, {} assignment(s) ===",
                id, request.getAssignments() != null ? request.getAssignments().size() : 0);
        return ResponseEntity.ok(workAssignmentService.assignWork(id, request));
    }
}
