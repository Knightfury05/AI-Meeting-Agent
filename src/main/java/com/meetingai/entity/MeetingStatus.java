package com.meetingai.entity;

public enum MeetingStatus {
    PENDING,        // uploaded, not yet processed
    TRANSCRIBING,   // Whisper is running
    SUMMARIZING,    // Ollama is running
    COMPLETED,      // fully processed, results available
    FAILED          // something went wrong — check logs
}