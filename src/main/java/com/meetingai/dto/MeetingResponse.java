package com.meetingai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/** Full response shape returned to the React frontend for a single meeting. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeetingResponse {
    private Long id;
    private String title;
    private String status;
    private String transcript;
    private String detectedLanguage;
    private String outputLanguage;
    private String summary;
    private List<Participant> participants;
    private List<Topic> topics;
    private List<ActionItem> actionItems;
    private List<String> decisions;
    private List<String> openQuestions;
    private LocalDateTime createdAt;
}