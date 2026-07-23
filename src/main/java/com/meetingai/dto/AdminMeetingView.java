package com.meetingai.dto;

import com.meetingai.entity.Meeting;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class AdminMeetingView {
    private Long id;
    private String title;
    private String status;
    private String outputLanguage;
    private String userEmail;
    private String userName;
    private String createdAt;

    public static AdminMeetingView from(Meeting meeting) {
        return AdminMeetingView.builder()
                .id(meeting.getId())
                .title(meeting.getTitle())
                .status(meeting.getStatus().name())
                .outputLanguage(meeting.getOutputLanguage())
                .userEmail(meeting.getUser().getEmail())
                .userName(meeting.getUser().getName())
                .createdAt(meeting.getCreatedAt() != null ? meeting.getCreatedAt().toString() : null)
                .build();
    }
}
