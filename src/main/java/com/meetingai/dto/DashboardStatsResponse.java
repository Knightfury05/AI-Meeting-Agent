package com.meetingai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardStatsResponse {
    private long totalUsers;
    private long adminUsers;
    private long regularUsers;
    private long totalMeetings;
    private long inProgressMeetings;
    private Map<String, Long> statusBreakdown;
}
