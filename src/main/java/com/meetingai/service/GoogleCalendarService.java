package com.meetingai.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.client.util.DateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GoogleCalendarService {

    private final GoogleOAuthService googleOAuthService;


    /**
     * Creates a Google Calendar service client from an
     * already-resolved credential.
     */
    private Calendar getCalendarClient(
            Credential credential
    ) throws Exception {

        return new Calendar.Builder(
                com.google.api.client.googleapis.javanet
                        .GoogleNetHttpTransport
                        .newTrustedTransport(),

                com.google.api.client.json.gson
                        .GsonFactory
                        .getDefaultInstance(),

                credential
        )
                .setApplicationName(
                        "MeetingAI"
                )
                .build();
    }


    /**
     * Creates a Google Calendar service client
     * using the currently authenticated user's
     * Google OAuth credentials.
     *
     * Only usable from a request thread with a populated
     * SecurityContext (i.e. from a controller). Background jobs
     * must use getCalendarClient(Long userId) instead.
     */
    private Calendar getCalendarClient()
            throws Exception {

        return getCalendarClient(
                googleOAuthService
                        .getValidCredential()
        );
    }


    /**
     * Creates a Google Calendar service client for a specific user
     * (by ID), independent of the current request's SecurityContext.
     *
     * Used by background jobs — e.g. the async meeting pipeline —
     * that already know which user they're acting on behalf of but
     * don't run on that user's original request thread.
     */
    private Calendar getCalendarClient(
            Long userId
    ) throws Exception {

        return getCalendarClient(
                googleOAuthService
                        .getValidCredential(userId)
        );
    }


    /**
     * Builds a Google Calendar Event object from the given fields.
     * Pure construction — no network call — shared by both the
     * current-user and userId-scoped create paths.
     */
    private Event buildEvent(
            String title,
            String description,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String timeZone,
            List<String> attendeeEmails
    ) {

        Event event =
                new Event()
                        .setSummary(title)
                        .setDescription(description);


        /*
         * Convert Java LocalDateTime
         * to Google Calendar DateTime.
         */
        ZoneId zoneId =
                ZoneId.of(timeZone);


        long startMillis =
                startTime
                        .atZone(zoneId)
                        .toInstant()
                        .toEpochMilli();


        long endMillis =
                endTime
                        .atZone(zoneId)
                        .toInstant()
                        .toEpochMilli();


        EventDateTime eventStart =
                new EventDateTime()
                        .setDateTime(
                                new DateTime(
                                        startMillis
                                )
                        )
                        .setTimeZone(
                                timeZone
                        );


        EventDateTime eventEnd =
                new EventDateTime()
                        .setDateTime(
                                new DateTime(
                                        endMillis
                                )
                        )
                        .setTimeZone(
                                timeZone
                        );


        event.setStart(
                eventStart
        );

        event.setEnd(
                eventEnd
        );


        /*
         * Add attendees if provided.
         */
        if (attendeeEmails != null
                && !attendeeEmails.isEmpty()) {

            List<EventAttendee> attendees =
                    new ArrayList<>();


            for (String email :
                    attendeeEmails) {

                if (email == null
                        || email.isBlank()) {

                    continue;
                }


                EventAttendee attendee =
                        new EventAttendee()
                                .setEmail(
                                        email.trim()
                                );


                attendees.add(
                        attendee
                );
            }


            if (!attendees.isEmpty()) {

                event.setAttendees(
                        attendees
                );
            }
        }


        return event;
    }


    /**
     * Creates a new event in the user's
     * primary Google Calendar.
     *
     * @param title Event title
     * @param description Event description
     * @param startTime Event start time
     * @param endTime Event end time
     * @param timeZone Time zone
     * @param attendeeEmails Optional attendee email addresses
     *
     * @return Created Google Calendar Event
     */
    public Event createEvent(
            String title,
            String description,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String timeZone,
            List<String> attendeeEmails
    ) throws Exception {

        Calendar calendar =
                getCalendarClient();

        Event event =
                buildEvent(
                        title,
                        description,
                        startTime,
                        endTime,
                        timeZone,
                        attendeeEmails
                );

        return calendar
                .events()
                .insert(
                        "primary",
                        event
                )
                .execute();
    }


    /**
     * Creates a new event in a specific user's primary Google
     * Calendar, independent of the current request's SecurityContext.
     *
     * Used by background jobs (e.g. the meeting reminder pipeline)
     * that already know which user's calendar to write to.
     *
     * @param userId ID of the user whose calendar the event goes in
     * @param title Event title
     * @param description Event description
     * @param startTime Event start time
     * @param endTime Event end time
     * @param timeZone Time zone
     * @param attendeeEmails Optional attendee email addresses
     *
     * @return Created Google Calendar Event
     */
    public Event createEventForUser(
            Long userId,
            String title,
            String description,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String timeZone,
            List<String> attendeeEmails
    ) throws Exception {

        Calendar calendar =
                getCalendarClient(userId);

        Event event =
                buildEvent(
                        title,
                        description,
                        startTime,
                        endTime,
                        timeZone,
                        attendeeEmails
                );

        return calendar
                .events()
                .insert(
                        "primary",
                        event
                )
                .execute();
    }


    /**
     * Creates a simple calendar event
     * without attendees.
     */
    public Event createEvent(
            String title,
            String description,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String timeZone
    ) throws Exception {

        return createEvent(
                title,
                description,
                startTime,
                endTime,
                timeZone,
                null
        );
    }


    /**
     * Retrieves upcoming events from
     * the user's primary calendar.
     *
     * @param maxResults Maximum number of events
     *
     * @return List of upcoming events
     */
    public List<Event> getUpcomingEvents(
            int maxResults
    ) throws Exception {

        Calendar calendar =
                getCalendarClient();


        /*
         * Current time in milliseconds.
         */
        long currentTimeMillis =
                System.currentTimeMillis();


        /*
         * Retrieve upcoming events.
         */
        Events events =
                calendar
                        .events()
                        .list(
                                "primary"
                        )
                        .setMaxResults(
                                maxResults
                        )
                        .setTimeMin(
                                new DateTime(
                                        currentTimeMillis
                                )
                        )
                        .setOrderBy(
                                "startTime"
                        )
                        .setSingleEvents(
                                true
                        )
                        .execute();


        return events
                .getItems();
    }


    /**
     * Retrieves upcoming events using
     * a default limit of 20 events.
     */
    public List<Event> getUpcomingEvents()
            throws Exception {

        return getUpcomingEvents(
                20
        );
    }


    /**
     * Retrieves a specific event
     * using its Google Calendar event ID.
     *
     * @param eventId Google Calendar event ID
     *
     * @return Google Calendar Event
     */
    public Event getEvent(
            String eventId
    ) throws Exception {

        Calendar calendar =
                getCalendarClient();


        return calendar
                .events()
                .get(
                        "primary",
                        eventId
                )
                .execute();
    }


    /**
     * Updates an existing Google Calendar event.
     *
     * @param eventId Google Calendar event ID
     * @param title New title
     * @param description New description
     * @param startTime New start time
     * @param endTime New end time
     * @param timeZone Time zone
     *
     * @return Updated event
     */
    public Event updateEvent(
            String eventId,
            String title,
            String description,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String timeZone
    ) throws Exception {

        Calendar calendar =
                getCalendarClient();


        /*
         * Retrieve existing event.
         */
        Event event =
                calendar
                        .events()
                        .get(
                                "primary",
                                eventId
                        )
                        .execute();


        /*
         * Update title.
         */
        if (title != null) {

            event.setSummary(
                    title
            );
        }


        /*
         * Update description.
         */
        if (description != null) {

            event.setDescription(
                    description
            );
        }


        /*
         * Update start and end times.
         */
        if (startTime != null
                && endTime != null) {

            ZoneId zoneId =
                    ZoneId.of(
                            timeZone
                    );


            long startMillis =
                    startTime
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli();


            long endMillis =
                    endTime
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli();


            EventDateTime eventStart =
                    new EventDateTime()
                            .setDateTime(
                                    new DateTime(
                                            startMillis
                                    )
                            )
                            .setTimeZone(
                                    timeZone
                            );


            EventDateTime eventEnd =
                    new EventDateTime()
                            .setDateTime(
                                    new DateTime(
                                            endMillis
                                    )
                            )
                            .setTimeZone(
                                    timeZone
                            );


            event.setStart(
                    eventStart
            );

            event.setEnd(
                    eventEnd
            );
        }


        /*
         * Save changes to Google Calendar.
         */
        return calendar
                .events()
                .update(
                        "primary",
                        eventId,
                        event
                )
                .execute();
    }


    /**
     * Deletes an event from the user's
     * primary Google Calendar.
     *
     * @param eventId Google Calendar event ID
     */
    public void deleteEvent(
            String eventId
    ) throws Exception {

        Calendar calendar =
                getCalendarClient();


        calendar
                .events()
                .delete(
                        "primary",
                        eventId
                )
                .execute();
    }
}