package com.meetingai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for POST /api/meetings/{id}/translate.
 * targetLanguage is the human-readable name (e.g. "Tamil", "Spanish") that
 * the sidebar dropdown sends — validated against the supported list in
 * MeetingTranslationService.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TranslateMeetingRequest {

    @NotBlank(message = "targetLanguage is required")
    private String targetLanguage;
}
