package com.meetingai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class SystemStatsResponse {
    private long totalUsers;
    private long totalMeetings;
    private long pendingCount;
    private long completedCount;
    private long failedCount;
    private long processingCount;
}
