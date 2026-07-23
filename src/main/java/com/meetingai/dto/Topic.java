package com.meetingai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single agenda point / discussion topic within a meeting, as you'd see
 * in a real Minutes of Meeting (MOM) document — e.g. "API Redesign",
 * "Mobile Crash Issue" — each with its own short discussion note.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Topic {
    private String title;       // e.g. "API Redesign"
    private String discussion;  // 1-2 sentence note on what was said/decided about it
}