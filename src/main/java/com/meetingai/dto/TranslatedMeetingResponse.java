package com.meetingai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Response shape for POST /api/meetings/{id}/translate. Mirrors the
 * analysis fields of MeetingResponse (summary, topics, action items,
 * decisions, open questions) but with every value translated into the
 * requested language — same structure, so the frontend can reuse its
 * existing renderers.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranslatedMeetingResponse {
    private Long meetingId;
    private String targetLanguage;
    private String summary;
    private List<Topic> topics;
    private List<ActionItem> actionItems;
    private List<String> decisions;
    private List<String> openQuestions;
}
