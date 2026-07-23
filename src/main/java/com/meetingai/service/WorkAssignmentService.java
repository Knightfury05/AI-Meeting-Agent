package com.meetingai.service;

import com.meetingai.dto.AssignWorkRequest;
import com.meetingai.dto.AssignWorkResponse;
import com.meetingai.entity.Meeting;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the "Assign Work" action from the meeting workspace UI.
 *
 * For each assignment row:
 *   1. Sends a task email via Gmail (always, if email + task are present).
 *   2. Optionally creates a Google Calendar event with the assignee as an
 *      attendee (only when createCalendarEvent = true and startDateTime
 *      is provided).
 *
 * Email and calendar are attempted independently — a calendar failure does
 * not roll back the email, and vice versa. The response reports per-row
 * results for both actions so the frontend can show granular feedback.
 *
 * Runs synchronously on the request thread because GmailService and
 * GoogleCalendarService both need the current request's SecurityContext
 * to resolve the user's Google OAuth credential.
 */
@Service
@RequiredArgsConstructor
public class WorkAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(WorkAssignmentService.class);

    private static final String DEFAULT_TIMEZONE = "UTC";
    private static final DateTimeFormatter DT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final MeetingService meetingService;
    private final GmailService gmailService;
    private final GoogleCalendarService googleCalendarService;

    /**
     * Processes all assignment rows for the given meeting.
     * Ownership of the meeting is verified via MeetingService.
     * Each row is attempted independently — one failure does not
     * prevent the rest from being processed.
     */
    public AssignWorkResponse assignWork(Long meetingId, AssignWorkRequest request) {
        Meeting meeting = meetingService.getOwnedMeetingEntity(meetingId);

        List<AssignWorkResponse.Result> results = new ArrayList<>();

        if (request.getAssignments() == null || request.getAssignments().isEmpty()) {
            return new AssignWorkResponse(results);
        }

        for (AssignWorkRequest.Assignment assignment : request.getAssignments()) {
            String name  = trimOrNull(assignment.getName());
            String email = trimOrNull(assignment.getEmail());
            String task  = trimOrNull(assignment.getTask());

            if (email == null || task == null) {
                results.add(new AssignWorkResponse.Result(
                        name, email, false, "Missing email or task", false, null));
                continue;
            }

            // ── Step 1: Send task email ───────────────────────────────────
            boolean emailSent  = false;
            String  emailError = null;

            try {
                String subject = "Action item from meeting: " + safeTitle(meeting.getTitle());
                String body    = buildEmailBody(meeting.getTitle(), name, task, assignment.getDeadline());

                gmailService.sendEmail(email, subject, body);
                emailSent = true;
                log.info("[AssignWork] meeting={} — email sent to {}", meetingId, email);

            } catch (Exception e) {
                emailError = e.getMessage();
                log.error("[AssignWork] meeting={} — email failed for {}: {}",
                        meetingId, email, e.getMessage(), e);
            }

            // ── Step 2: Create calendar event (optional) ──────────────────
            boolean calendarCreated = false;
            String  calendarError   = null;

            if (assignment.isCreateCalendarEvent()) {
                try {
                    calendarCreated = createCalendarEvent(meeting, assignment, name, email, task);
                    log.info("[AssignWork] meeting={} — calendar event created for {}", meetingId, email);

                } catch (Exception e) {
                    calendarError = e.getMessage();
                    log.error("[AssignWork] meeting={} — calendar event failed for {}: {}",
                            meetingId, email, e.getMessage(), e);
                }
            }

            results.add(new AssignWorkResponse.Result(
                    name, email, emailSent, emailError, calendarCreated, calendarError));
        }

        return new AssignWorkResponse(results);
    }

    // ── Calendar event creation ───────────────────────────────────────────

    /**
     * Creates a Google Calendar event for a single assignment.
     *
     * The event title is "Task: {task}" and the description includes the
     * full context (meeting name + deadline). The assignee is added as an
     * attendee so the invite lands in their calendar too.
     *
     * If endDateTime is not provided, defaults to startDateTime + 1 hour.
     * If timeZone is not provided, defaults to UTC.
     *
     * @return true if the event was created successfully
     */
    private boolean createCalendarEvent(
            Meeting meeting,
            AssignWorkRequest.Assignment assignment,
            String name,
            String email,
            String task
    ) throws Exception {

        String startRaw = trimOrNull(assignment.getStartDateTime());
        if (startRaw == null) {
            throw new IllegalArgumentException(
                    "startDateTime is required when createCalendarEvent = true");
        }

        LocalDateTime startTime = parseDateTime(startRaw);
        LocalDateTime endTime;

        String endRaw = trimOrNull(assignment.getEndDateTime());
        if (endRaw != null) {
            endTime = parseDateTime(endRaw);
        } else {
            // Default: 1-hour slot if no end time provided
            endTime = startTime.plusHours(1);
            log.debug("[AssignWork] No endDateTime provided — defaulting to startTime + 1 hour");
        }

        String timeZone = assignment.getTimeZone() != null
                && !assignment.getTimeZone().isBlank()
                ? assignment.getTimeZone().trim()
                : DEFAULT_TIMEZONE;

        String eventTitle = "Task: " + task;

        String eventDescription = buildCalendarDescription(
                meeting.getTitle(), name, task, assignment.getDeadline());

        // Add the assignee as an attendee so the invite goes to their calendar
        List<String> attendees = List.of(email);

        googleCalendarService.createEvent(
                eventTitle,
                eventDescription,
                startTime,
                endTime,
                timeZone,
                attendees
        );

        return true;
    }

    // ── Body builders ─────────────────────────────────────────────────────

    private String buildEmailBody(
            String meetingTitle, String name, String task, String deadline) {

        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(name != null ? name : "there").append(",\n\n");
        sb.append("You've been assigned the following task from the meeting \"")
                .append(safeTitle(meetingTitle)).append("\":\n\n");
        sb.append("Task: ").append(task).append("\n");

        if (deadline != null && !deadline.isBlank()
                && !deadline.equalsIgnoreCase("null")
                && !deadline.equalsIgnoreCase("tbd")) {
            sb.append("Deadline: ").append(deadline).append("\n");
        }

        sb.append("\nA calendar event has been added to your Google Calendar for this task.")
                .append("\n\n— Sent automatically by MeetingAI");

        return sb.toString();
    }

    private String buildCalendarDescription(
            String meetingTitle, String name, String task, String deadline) {

        StringBuilder sb = new StringBuilder();
        sb.append("Meeting: ").append(safeTitle(meetingTitle)).append("\n");
        sb.append("Assigned to: ").append(name != null ? name : "—").append("\n");
        sb.append("Task: ").append(task).append("\n");

        if (deadline != null && !deadline.isBlank()
                && !deadline.equalsIgnoreCase("null")
                && !deadline.equalsIgnoreCase("tbd")) {
            sb.append("Deadline: ").append(deadline).append("\n");
        }

        sb.append("\n— Created automatically by MeetingAI");
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private LocalDateTime parseDateTime(String raw) {
        try {
            return LocalDateTime.parse(raw, DT_FORMATTER);
        } catch (DateTimeParseException e) {
            // Also try ISO_LOCAL_DATE_TIME as fallback (includes fractional seconds)
            try {
                return LocalDateTime.parse(raw);
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException(
                        "Invalid dateTime format: \"" + raw
                                + "\". Expected yyyy-MM-dd'T'HH:mm:ss (e.g. 2026-07-25T10:00:00)");
            }
        }
    }

    private String safeTitle(String title) {
        return (title != null && !title.isBlank()) ? title : "Untitled meeting";
    }

    private String trimOrNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}