package com.meetingai.service;

import com.meetingai.dto.ActionItem;
import com.meetingai.dto.MeetingAnalysisResult;
import com.meetingai.dto.Topic;
import com.meetingai.entity.Meeting;
import com.meetingai.entity.Reminder;
import com.meetingai.entity.User;
import com.meetingai.repository.MeetingRepository;
import com.meetingai.repository.ReminderRepository;
import com.meetingai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Final step of the meeting pipeline: once a meeting's summary and action
 * items are ready, notify the user through their connected Google account —
 * one email with the full write-up, plus Google Calendar event(s) so it
 * actually shows up as a reminder instead of just sitting in an inbox.
 *
 * Runs on the same background thread as the rest of the async pipeline
 * (see MeetingProcessingService), so — like GoogleCalendarService/
 * GmailService — it never touches CurrentUserProvider/SecurityContextHolder.
 * Everything it needs (userId, meeting id/title, the already-parsed
 * analysis result) is passed in explicitly by the caller.
 *
 * Deadlines on individual action items are free-text from the LLM (e.g.
 * "Friday 2pm", or the equivalent in whatever output language was chosen)
 * and can't be reliably parsed in general. So this takes a hybrid approach:
 *  - Always create one "recap" calendar event shortly after processing
 *    finishes, with the full summary/action items in its description —
 *    this is the guaranteed reminder that never depends on date parsing.
 *  - Best-effort: additionally create one calendar event per action item
 *    whose deadline happens to parse as an actual date (ISO format, or a
 *    handful of common explicit formats). Anything that doesn't parse is
 *    simply left out of the calendar (it's still in the recap event and
 *    the email) rather than guessed at.
 *
 * A missing Google connection, or any send/create failure, is logged and
 * recorded on the Reminder row — it never affects the meeting's own
 * COMPLETED status, since the meeting analysis itself already succeeded.
 */
@Service
@RequiredArgsConstructor
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private static final int MAX_STORED_ERROR_LENGTH = 2000;

    private final GoogleOAuthService googleOAuthService;
    private final GoogleCalendarService googleCalendarService;
    private final GmailService gmailService;
    private final ReminderRepository reminderRepository;
    private final MeetingRepository meetingRepository;
    private final UserRepository userRepository;

    @Value("${app.reminders.enabled:true}")
    private boolean remindersEnabled;

    @Value("${app.reminders.default-timezone:Asia/Kolkata}")
    private String defaultTimeZone;

    @Value("${app.reminders.recap-event-delay-minutes:60}")
    private long recapEventDelayMinutes;

    @Value("${app.reminders.recap-event-duration-minutes:30}")
    private long recapEventDurationMinutes;

    @Value("${app.reminders.action-item-default-hour:9}")
    private int actionItemDefaultHour;

    @Value("${app.reminders.action-item-event-duration-minutes:30}")
    private long actionItemEventDurationMinutes;

    private static final DateTimeFormatter[] DATE_ONLY_FORMATS = new DateTimeFormatter[] {
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
    };

    /**
     * Sends the post-processing reminder (email + calendar) for a
     * completed meeting. Safe to call even if the user never connected
     * Google — in that case it just records that fact and returns.
     *
     * Idempotent: if a Reminder row already exists for this meeting
     * (e.g. this somehow got triggered twice), it does nothing on the
     * second call rather than sending duplicate emails/events.
     *
     * @param meetingId ID of the now-COMPLETED meeting
     * @param userId ID of the meeting's owner
     * @param meetingTitle Meeting title, for the email subject and event titles
     * @param result The already-parsed summary/topics/action items/decisions
     */
    @Transactional
    public void sendMeetingReminders(Long meetingId, Long userId, String meetingTitle, MeetingAnalysisResult result) {

        if (!remindersEnabled) {
            log.info("[Reminder] disabled via app.reminders.enabled — skipping meeting id={}", meetingId);
            return;
        }

        if (reminderRepository.existsByMeetingId(meetingId)) {
            log.info("[Reminder] meeting id={} already has a reminder record — skipping duplicate run", meetingId);
            return;
        }

        Meeting meetingRef = meetingRepository.getReferenceById(meetingId);
        Reminder.ReminderBuilder reminder = Reminder.builder().meeting(meetingRef);

        boolean connected = googleOAuthService.isGoogleConnectedForUser(userId);
        reminder.googleConnected(connected);

        if (!connected) {
            reminderRepository.save(reminder.build());
            log.info("[Reminder] user id={} has not connected Google — skipping calendar/email reminder for meeting id={}",
                    userId, meetingId);
            return;
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            reminder.emailSent(false).emailError("User " + userId + " not found or has no email on file");
            reminderRepository.save(reminder.build());
            log.error("[Reminder] meeting id={} — could not resolve a user/email for user id={}", meetingId, userId);
            return;
        }

        // 1. Email — the guaranteed, always-attempted notification.
        try {
            String subject = "Meeting Summary: " + safeTitle(meetingTitle);
            String body = buildPlainTextWriteup(meetingTitle, result,
                    "Here is the summary and action items from your meeting.");

            gmailService.sendEmailForUser(userId, user.getEmail(), subject, body);

            reminder.emailSent(true);
            log.info("[Reminder] meeting id={} — reminder email sent to {}", meetingId, user.getEmail());

        } catch (Exception e) {
            reminder.emailSent(false).emailError(truncate(e.getMessage()));
            log.error("[Reminder] meeting id={} — failed to send reminder email: {}", meetingId, e.getMessage(), e);
        }

        // 2. Calendar — one guaranteed recap event, plus best-effort
        //    per-action-item events for deadlines that actually parse.
        int eventsCreated = 0;
        StringBuilder calendarErrors = new StringBuilder();

        try {
            LocalDateTime recapStart = LocalDateTime.now().plusMinutes(recapEventDelayMinutes);
            LocalDateTime recapEnd = recapStart.plusMinutes(recapEventDurationMinutes);

            String description = buildPlainTextWriteup(meetingTitle, result,
                    "Auto-generated recap of this meeting's summary and action items.");

            googleCalendarService.createEventForUser(
                    userId,
                    "Review: " + safeTitle(meetingTitle),
                    description,
                    recapStart,
                    recapEnd,
                    defaultTimeZone,
                    null
            );
            eventsCreated++;

        } catch (Exception e) {
            calendarErrors.append("recap event: ").append(e.getMessage()).append("; ");
            log.error("[Reminder] meeting id={} — failed to create recap calendar event: {}",
                    meetingId, e.getMessage(), e);
        }

        if (result.getActionItems() != null) {
            for (ActionItem item : result.getActionItems()) {

                Optional<LocalDateTime> parsedDeadline = parseDeadline(item.getDeadline());
                if (parsedDeadline.isEmpty()) {
                    continue; // free-text deadline we can't safely turn into a date — already covered by the recap event + email
                }

                try {
                    LocalDateTime start = parsedDeadline.get();
                    LocalDateTime end = start.plusMinutes(actionItemEventDurationMinutes);

                    String owner = (item.getOwner() != null && !item.getOwner().isBlank())
                            ? item.getOwner()
                            : "team";

                    String title = "Action item: " + truncate(item.getTask(), 200);
                    String description = "From meeting: " + safeTitle(meetingTitle)
                            + "\nOwner: " + owner
                            + "\n\n" + item.getTask();

                    googleCalendarService.createEventForUser(
                            userId, title, description, start, end, defaultTimeZone, null
                    );
                    eventsCreated++;

                } catch (Exception e) {
                    calendarErrors.append("action item '").append(truncate(item.getTask(), 60))
                            .append("': ").append(e.getMessage()).append("; ");
                    log.error("[Reminder] meeting id={} — failed to create calendar event for action item '{}': {}",
                            meetingId, item.getTask(), e.getMessage(), e);
                }
            }
        }

        reminder.calendarEventsCreated(eventsCreated);
        if (!calendarErrors.isEmpty()) {
            reminder.calendarError(truncate(calendarErrors.toString()));
        }

        Reminder toSave = reminder.build();
        reminderRepository.save(toSave);
        log.info("[Reminder] meeting id={} — reminder pipeline finished. emailSent={}, calendarEventsCreated={}",
                meetingId, toSave.isEmailSent(), eventsCreated);
    }

    /**
     * Attempts to parse a free-text deadline into an actual date/time.
     * Only handles unambiguous, explicit formats (ISO date/date-time, and
     * a handful of common written-out formats) — relative phrases like
     * "Friday" or "next week" are deliberately left unparsed rather than
     * guessed at, since a wrong guess is worse than no calendar event.
     */
    private Optional<LocalDateTime> parseDeadline(String deadline) {
        if (deadline == null) {
            return Optional.empty();
        }

        String cleaned = deadline.trim();
        if (cleaned.isEmpty()
                || cleaned.equalsIgnoreCase("null")
                || cleaned.equalsIgnoreCase("none")
                || cleaned.equalsIgnoreCase("n/a")
                || cleaned.equalsIgnoreCase("tbd")) {
            return Optional.empty();
        }

        // Full ISO date-time, e.g. "2025-08-01T14:00:00"
        try {
            return Optional.of(LocalDateTime.parse(cleaned));
        } catch (DateTimeParseException ignored) {
            // fall through
        }

        // ISO date only, e.g. "2025-08-01" — defaults to the configured hour
        try {
            return Optional.of(LocalDate.parse(cleaned).atTime(actionItemDefaultHour, 0));
        } catch (DateTimeParseException ignored) {
            // fall through
        }

        for (DateTimeFormatter format : DATE_ONLY_FORMATS) {
            try {
                LocalDate date = LocalDate.parse(cleaned, format);
                return Optional.of(date.atTime(actionItemDefaultHour, 0));
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }

        return Optional.empty();
    }

    /**
     * Plain-text write-up shared by both the email body and the recap
     * calendar event's description, so the two channels always agree.
     */
    private String buildPlainTextWriteup(String meetingTitle, MeetingAnalysisResult result, String intro) {
        StringBuilder sb = new StringBuilder();

        sb.append(intro).append("\n\n");
        sb.append("Meeting: ").append(safeTitle(meetingTitle)).append("\n\n");

        if (result.getSummary() != null && !result.getSummary().isBlank()) {
            sb.append("Summary:\n").append(result.getSummary()).append("\n\n");
        }

        List<Topic> topics = result.getTopics();
        if (topics != null && !topics.isEmpty()) {
            sb.append("Topics discussed:\n");
            for (Topic topic : topics) {
                sb.append("- ").append(topic.getTitle());
                if (topic.getDiscussion() != null && !topic.getDiscussion().isBlank()) {
                    sb.append(": ").append(topic.getDiscussion());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        List<ActionItem> actionItems = result.getActionItems();
        if (actionItems != null && !actionItems.isEmpty()) {
            sb.append("Action items:\n");
            for (ActionItem item : actionItems) {
                String owner = (item.getOwner() != null && !item.getOwner().isBlank()) ? item.getOwner() : "team";
                sb.append("- [").append(owner).append("] ").append(item.getTask());
                if (item.getDeadline() != null && !item.getDeadline().isBlank()
                        && !item.getDeadline().equalsIgnoreCase("null")) {
                    sb.append(" (deadline: ").append(item.getDeadline()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        List<String> decisions = result.getDecisions();
        if (decisions != null && !decisions.isEmpty()) {
            sb.append("Decisions:\n");
            for (String decision : decisions) {
                sb.append("- ").append(decision).append("\n");
            }
            sb.append("\n");
        }

        List<String> openQuestions = result.getOpenQuestions();
        if (openQuestions != null && !openQuestions.isEmpty()) {
            sb.append("Open questions:\n");
            for (String question : openQuestions) {
                sb.append("- ").append(question).append("\n");
            }
            sb.append("\n");
        }

        sb.append("— Sent automatically by MeetingAI");

        return sb.toString();
    }

    private String safeTitle(String title) {
        return (title != null && !title.isBlank()) ? title : "Untitled meeting";
    }

    private String truncate(String text) {
        return truncate(text, MAX_STORED_ERROR_LENGTH);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
