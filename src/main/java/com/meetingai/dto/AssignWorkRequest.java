package com.meetingai.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Body for POST /api/meetings/{id}/assign-work.
 *
 * Each Assignment row can optionally also create a Google Calendar event
 * for the assignee's deadline. The calendar fields are all optional:
 * if createCalendarEvent is false (or null), the calendar step is skipped
 * entirely and only the email is sent.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AssignWorkRequest {

    private List<Assignment> assignments;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Assignment {

        /** Assignee display name — used in the email greeting. */
        private String name;

        /** Assignee email — required for both email and calendar invite. */
        private String email;

        /** Task description — required. */
        private String task;

        /**
         * Deadline as a human-readable string (e.g. "Friday", "2026-07-25").
         * Used in the email body. Not used for calendar timing — use
         * startDateTime / endDateTime for that.
         */
        private String deadline;

        // ── Calendar event fields (all optional) ──────────────────────────

        /**
         * Whether to also create a Google Calendar event for this assignment.
         * Defaults to false — calendar is only created when explicitly requested.
         */
        private boolean createCalendarEvent;

        /**
         * Calendar event start time in ISO-8601 format (e.g. "2026-07-25T10:00:00").
         * Required when createCalendarEvent = true.
         */
        private String startDateTime;

        /**
         * Calendar event end time in ISO-8601 format (e.g. "2026-07-25T11:00:00").
         * Required when createCalendarEvent = true. If not provided, defaults
         * to startDateTime + 1 hour.
         */
        private String endDateTime;

        /**
         * IANA time zone for the calendar event (e.g. "Asia/Kolkata", "UTC").
         * Defaults to "UTC" if not provided.
         */
        private String timeZone;
    }
}