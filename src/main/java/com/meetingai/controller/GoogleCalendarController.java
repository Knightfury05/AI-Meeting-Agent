package com.meetingai.controller;

import com.google.api.services.calendar.model.Event;
import com.meetingai.service.GoogleCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/google/calendar")
@RequiredArgsConstructor
@Tag(name = "Google Calendar", description = "Create, read, update and delete Google Calendar events")
public class GoogleCalendarController {

    private final GoogleCalendarService googleCalendarService;


    /**
     * Create a Google Calendar event.
     *
     * POST:
     * /api/google/calendar/events
     *
     * Example request:
     *
     * {
     *   "title": "Team Meeting",
     *   "description": "Discuss project progress",
     *   "startTime": "2026-07-25T10:00:00",
     *   "endTime": "2026-07-25T11:00:00",
     *   "timeZone": "Asia/Kolkata",
     *   "attendeeEmails": [
     *      "example@gmail.com"
     *   ]
     * }
     */
    @PostMapping("/events")
    @Operation(summary = "Create a calendar event", description = "Creates a Google Calendar event with optional attendees.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Event created"),
        @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    public ResponseEntity<?> createEvent(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Event details", required = true,
                content = @Content(schema = @Schema(implementation = CreateEventRequest.class)))
            @RequestBody CreateEventRequest request
    ) {

        try {

            Event event =
                    googleCalendarService.createEvent(
                            request.title(),
                            request.description(),
                            request.startTime(),
                            request.endTime(),
                            request.timeZone(),
                            request.attendeeEmails()
                    );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(
                            Map.of(
                                    "success", true,
                                    "message",
                                    "Calendar event created successfully",
                                    "eventId",
                                    event.getId(),
                                    "eventLink",
                                    event.getHtmlLink() != null
                                            ? event.getHtmlLink()
                                            : ""
                            )
                    );

        } catch (Exception e) {

            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(
                            Map.of(
                                    "success", false,
                                    "message",
                                    "Failed to create calendar event",
                                    "error",
                                    e.getMessage()
                            )
                    );
        }
    }


    /**
     * Get upcoming Google Calendar events.
     *
     * GET:
     * /api/google/calendar/events
     *
     * Optional:
     * /api/google/calendar/events?maxResults=20
     */
    @GetMapping("/events")
    @Operation(summary = "List upcoming events", description = "Returns upcoming Google Calendar events for the connected account.")
    @ApiResponse(responseCode = "200", description = "Events retrieved")
    public ResponseEntity<?> getUpcomingEvents(
            @Parameter(description = "Maximum number of events to return (max 100)")
            @RequestParam(
                    defaultValue = "20"
            )
            int maxResults
    ) {

        try {

            /*
             * Prevent unreasonable values.
             */
            if (maxResults < 1) {

                maxResults = 20;
            }

            if (maxResults > 100) {

                maxResults = 100;
            }


            List<Event> events =
                    googleCalendarService
                            .getUpcomingEvents(
                                    maxResults
                            );


            return ResponseEntity.ok(
                    Map.of(
                            "success", true,
                            "count", events.size(),
                            "events", events
                    )
            );

        } catch (Exception e) {

            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(
                            Map.of(
                                    "success", false,
                                    "message",
                                    "Failed to retrieve calendar events",
                                    "error",
                                    e.getMessage()
                            )
                    );
        }
    }


    /**
     * Get a specific Google Calendar event.
     *
     * GET:
     * /api/google/calendar/events/{eventId}
     */
    @GetMapping("/events/{eventId}")
    @Operation(summary = "Get event by ID", description = "Returns a specific Google Calendar event.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event found"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
    public ResponseEntity<?> getEvent(
            @Parameter(description = "Google Calendar event ID")
            @PathVariable String eventId
    ) {

        try {

            Event event =
                    googleCalendarService
                            .getEvent(
                                    eventId
                            );


            return ResponseEntity.ok(
                    Map.of(
                            "success", true,
                            "event", event
                    )
            );

        } catch (Exception e) {

            return ResponseEntity
                    .status(
                            HttpStatus.NOT_FOUND
                    )
                    .body(
                            Map.of(
                                    "success", false,
                                    "message",
                                    "Calendar event not found",
                                    "error",
                                    e.getMessage()
                            )
                    );
        }
    }


    /**
     * Update an existing Google Calendar event.
     *
     * PUT:
     * /api/google/calendar/events/{eventId}
     */
    @PutMapping("/events/{eventId}")
    @Operation(summary = "Update an event", description = "Updates an existing Google Calendar event.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event updated"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
    public ResponseEntity<?> updateEvent(
            @Parameter(description = "Google Calendar event ID")
            @PathVariable String eventId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Updated event details", required = true,
                content = @Content(schema = @Schema(implementation = UpdateEventRequest.class)))
            @RequestBody UpdateEventRequest request
    ) {

        try {

            Event updatedEvent =
                    googleCalendarService.updateEvent(
                            eventId,
                            request.title(),
                            request.description(),
                            request.startTime(),
                            request.endTime(),
                            request.timeZone()
                    );


            return ResponseEntity.ok(
                    Map.of(
                            "success", true,
                            "message",
                            "Calendar event updated successfully",
                            "eventId",
                            updatedEvent.getId(),
                            "eventLink",
                            updatedEvent.getHtmlLink() != null
                                    ? updatedEvent.getHtmlLink()
                                    : ""
                    )
            );

        } catch (Exception e) {

            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(
                            Map.of(
                                    "success", false,
                                    "message",
                                    "Failed to update calendar event",
                                    "error",
                                    e.getMessage()
                            )
                    );
        }
    }


    /**
     * Delete a Google Calendar event.
     *
     * DELETE:
     * /api/google/calendar/events/{eventId}
     */
    @DeleteMapping("/events/{eventId}")
    @Operation(summary = "Delete an event", description = "Deletes a Google Calendar event.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event deleted"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
    public ResponseEntity<?> deleteEvent(
            @Parameter(description = "Google Calendar event ID")
            @PathVariable String eventId
    ) {

        try {

            googleCalendarService
                    .deleteEvent(
                            eventId
                    );


            return ResponseEntity.ok(
                    Map.of(
                            "success", true,
                            "message",
                            "Calendar event deleted successfully"
                    )
            );

        } catch (Exception e) {

            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(
                            Map.of(
                                    "success", false,
                                    "message",
                                    "Failed to delete calendar event",
                                    "error",
                                    e.getMessage()
                            )
                    );
        }
    }


    /**
     * Request DTO for creating an event.
     */
    public record CreateEventRequest(

            String title,

            String description,

            LocalDateTime startTime,

            LocalDateTime endTime,

            String timeZone,

            List<String> attendeeEmails

    ) {
    }


    /**
     * Request DTO for updating an event.
     */
    public record UpdateEventRequest(

            String title,

            String description,

            LocalDateTime startTime,

            LocalDateTime endTime,

            String timeZone

    ) {
    }
}