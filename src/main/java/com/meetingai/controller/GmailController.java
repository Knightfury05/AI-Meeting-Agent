package com.meetingai.controller;

import com.google.api.services.gmail.model.Message;
import com.meetingai.service.GmailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/google/gmail")
@RequiredArgsConstructor
@Tag(name = "Gmail", description = "Send emails via connected Gmail account")
public class GmailController {

    private final GmailService gmailService;


    /**
     * Send an email using the currently authenticated
     * user's Gmail account.
     *
     * POST:
     * /api/google/gmail/send
     *
     * Request body:
     *
     * {
     *   "to": "example@gmail.com",
     *   "subject": "Meeting Reminder",
     *   "body": "This is a reminder for your upcoming meeting."
     * }
     */
    @PostMapping("/send")
    @Operation(summary = "Send an email", description = "Sends an email using the current user's connected Gmail account.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Email sent successfully"),
        @ApiResponse(responseCode = "400", description = "Missing required fields (to, subject, body)")
    })
    public ResponseEntity<?> sendEmail(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Email details", required = true,
                content = @Content(schema = @Schema(implementation = SendEmailRequest.class)))
            @RequestBody SendEmailRequest request
    ) {

        try {

            /*
             * Validate request.
             */
            if (request.to() == null
                    || request.to().isBlank()) {

                return ResponseEntity
                        .badRequest()
                        .body(
                                Map.of(
                                        "success", false,
                                        "message",
                                        "Recipient email is required"
                                )
                        );
            }


            if (request.subject() == null
                    || request.subject().isBlank()) {

                return ResponseEntity
                        .badRequest()
                        .body(
                                Map.of(
                                        "success", false,
                                        "message",
                                        "Email subject is required"
                                )
                        );
            }


            if (request.body() == null
                    || request.body().isBlank()) {

                return ResponseEntity
                        .badRequest()
                        .body(
                                Map.of(
                                        "success", false,
                                        "message",
                                        "Email body is required"
                                )
                        );
            }


            /*
             * Send email through Gmail API.
             */
            Message sentMessage =
                    gmailService.sendEmail(
                            request.to(),
                            request.subject(),
                            request.body()
                    );


            /*
             * Return success response.
             */
            return ResponseEntity
                    .status(HttpStatus.OK)
                    .body(
                            Map.of(
                                    "success", true,
                                    "message",
                                    "Email sent successfully",
                                    "gmailMessageId",
                                    sentMessage.getId()
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
                                    "Failed to send email",
                                    "error",
                                    e.getMessage()
                            )
                    );
        }
    }


    /**
     * Request DTO for sending an email.
     */
    public record SendEmailRequest(

            String to,

            String subject,

            String body

    ) {
    }
}