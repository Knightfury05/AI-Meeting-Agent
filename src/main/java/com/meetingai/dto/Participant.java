package com.meetingai.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A person the AI identified as present in / addressed by the meeting,
 * inferred from names, introductions, and direct address in the
 * transcript text (there is no voice-biometric speaker identification
 * in this pipeline, so this is "who was talked to/about", not a verified
 * attendance list). Email is deliberately NOT produced by the AI — the
 * transcript never contains it — and is instead filled in by the user in
 * the "Assign Work" dialog before sending.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Participant {
    private String name;
    private String role; // job title / team, if mentioned — blank otherwise

    /**
     * The prompt asks the model for {"name": ..., "role": ...} objects, and
     * it follows that almost every time — but local models (Ollama) aren't
     * schema-constrained, and every so often one collapses a participant
     * down to a bare string (e.g. "Elkone" instead of {"name": "Elkone"}).
     * Without this, Jackson has no String-argument creator to fall back on
     * and the whole pipeline run fails with a MismatchedInputException,
     * even though the plain string is perfectly recoverable data.
     */
    @JsonCreator
    public static Participant fromName(String name) {
        return new Participant(name, "");
    }
}
