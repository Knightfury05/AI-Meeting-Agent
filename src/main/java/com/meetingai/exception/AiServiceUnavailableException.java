package com.meetingai.exception;

/**
 * Thrown when a call to the local Ollama service fails (not running,
 * wrong host, model not pulled, etc). Distinct from a generic
 * RuntimeException so GlobalExceptionHandler can surface the actual
 * "make sure Ollama is running" message instead of the generic 500 body —
 * unlike a real internal error, this message contains nothing sensitive
 * and is exactly what a local-dev user needs to see to fix it themselves.
 */
public class AiServiceUnavailableException extends RuntimeException {
    public AiServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
