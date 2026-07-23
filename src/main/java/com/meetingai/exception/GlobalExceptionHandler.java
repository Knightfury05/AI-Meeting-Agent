package com.meetingai.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Catches exceptions thrown anywhere in the request pipeline and returns a
 * consistent JSON error body.
 *
 * SECURITY NOTE: for client errors (4xx — bad input, validation, auth) the
 * actual exception message is safe and useful to return, since it's just
 * describing what the client did wrong. For unexpected server errors (5xx)
 * the real exception message is logged in full but NEVER sent to the
 * client — stack traces, SQL fragments, file paths, and internal class
 * names are exactly the kind of detail that should stay server-side. The
 * client gets a generic message instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String GENERIC_SERVER_ERROR_MESSAGE =
            "Something went wrong on our end. Please try again, and contact support if it keeps happening.";

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException ex) {
        log.warn("Missing multipart part: '{}'", ex.getRequestPartName());
        return buildResponse(HttpStatus.BAD_REQUEST,
                "Missing required part '" + ex.getRequestPartName() + "'. " +
                        "Check that the form-data key is named exactly '" + ex.getRequestPartName() +
                        "' and its type is set to File.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: '{}'", ex.getParameterName());
        return buildResponse(HttpStatus.BAD_REQUEST,
                "Missing required parameter '" + ex.getParameterName() + "'.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Uploaded file exceeded max size: {}", ex.getMessage());
        return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE,
                "Uploaded file is too large. Max allowed size is set in application.yml.");
    }

    /** Triggered by @Valid on @RequestBody DTOs (RegisterRequest, LoginRequest). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        log.warn("Validation failed: {}", message);
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    /** Bad credentials on login, or any other Spring Security authentication failure. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex) {
        log.info("Authentication failed: {}", ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /** Used for "resource not found" (e.g. a meeting that doesn't exist or isn't yours). */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException ex) {
        log.info("Resource not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** Bad client input that isn't a validation-annotation failure (e.g. bad file type, duplicate email). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        log.info("Bad request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * The local Ollama call failed (not running, wrong host, model not
     * pulled). Unlike the generic 500 case, this message is safe to show
     * as-is — it's actionable local-dev guidance, not internal detail.
     */
    @ExceptionHandler(com.meetingai.exception.AiServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAiUnavailable(com.meetingai.exception.AiServiceUnavailableException ex) {
        log.warn("AI service unavailable: {}", ex.getMessage());
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /**
     * Catch-all for anything else. The real message and full stack trace
     * are logged for debugging, but only a generic message goes to the
     * client — see the class-level note on why.
     */
    @ExceptionHandler({RuntimeException.class, Exception.class})
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR_MESSAGE);
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
