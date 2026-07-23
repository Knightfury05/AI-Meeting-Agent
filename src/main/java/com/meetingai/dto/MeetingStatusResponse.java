package com.meetingai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Returned immediately from POST /api/meetings/analyze, before processing
 * has finished. The frontend polls GET /api/meetings/{id} (which returns
 * the full MeetingResponse) until status is COMPLETED or FAILED.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeetingStatusResponse {
    private Long id;
    private String title;
    private String status;
}
