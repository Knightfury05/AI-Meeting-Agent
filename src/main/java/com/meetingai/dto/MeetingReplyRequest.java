package com.meetingai.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MeetingReplyRequest {

    private String recipientName;

    @NotBlank(message = "Recipient email is required")
    @Email(message = "Recipient email must be a valid email address")
    private String recipientEmail;

    /**
     * Optional reviewed draft. When both are present, the backend skips AI
     * generation and sends exactly this subject/body (used after the UI
     * shows the user the generated draft for review).
     */
    private String subject;
    private String body;
}
