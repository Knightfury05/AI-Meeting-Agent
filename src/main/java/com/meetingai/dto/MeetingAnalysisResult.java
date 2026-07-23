package com.meetingai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Maps directly to the JSON shape we ask the LLM to return in AIService.
 * Field names use @JsonProperty to match the snake_case keys in the prompt
 * while keeping camelCase in Java.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MeetingAnalysisResult {

    private String summary;

    private List<Participant> participants;

    private List<Topic> topics;

    @JsonProperty("action_items")
    private List<ActionItem> actionItems;

    private List<String> decisions;

    @JsonProperty("open_questions")
    private List<String> openQuestions;
}