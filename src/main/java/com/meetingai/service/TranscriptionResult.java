package com.meetingai.service;

/**
 * Result of running Whisper on an audio file: the transcript text plus
 * the language Whisper auto-detected (ISO 639-1 code, e.g. "en", "hi", "ta").
 */
public class TranscriptionResult {
    private final String text;
    private final String language;

    public TranscriptionResult(String text, String language) {
        this.text = text;
        this.language = language;
    }

    public String getText() {
        return text;
    }

    public String getLanguage() {
        return language;
    }
}