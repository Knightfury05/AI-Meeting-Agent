package com.meetingai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminRegisterRequest extends RegisterRequest {

    @NotBlank(message = "Admin registration code is required")
    private String adminCode;
}
