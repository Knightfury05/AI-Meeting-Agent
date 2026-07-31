package com.meetingai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeetingReplyResponse {
    private String recipientName;
    private String recipientEmail;
    private String subject;
    private String body;
    private String gmailMessageId;
}
