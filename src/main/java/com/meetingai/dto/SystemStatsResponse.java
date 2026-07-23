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
    private long meetingsPending;
    private long meetingsCompleted;
    private long meetingsFailed;
    private long meetingsProcessing;
}
