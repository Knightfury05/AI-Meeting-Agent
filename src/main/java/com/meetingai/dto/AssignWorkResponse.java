package com.meetingai.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AssignWorkResponse {

    private List<Result> results;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Result {

        private String name;
        private String email;

        /** Whether the task email was sent successfully. */
        private boolean sent;

        /** Email error message — null when sent = true. */
        private String error;

        /**
         * Whether a Google Calendar event was created.
         * false if createCalendarEvent was not requested,
         * or if calendar creation failed.
         */
        private boolean calendarCreated;

        /**
         * Calendar error message — null when calendarCreated = true
         * or when calendar creation was not requested.
         */
        private String calendarError;

        /** Convenience constructor for email-only results (no calendar). */
        public Result(String name, String email, boolean sent, String error) {
            this.name = name;
            this.email = email;
            this.sent = sent;
            this.error = error;
            this.calendarCreated = false;
            this.calendarError = null;
        }
    }
}