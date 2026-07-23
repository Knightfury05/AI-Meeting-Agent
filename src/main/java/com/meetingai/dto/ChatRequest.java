package com.meetingai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatRequest {

    @NotBlank(message = "Message is required")
    @Size(max = 2000, message = "Message must be 2000 characters or fewer")
    private String message;
}
