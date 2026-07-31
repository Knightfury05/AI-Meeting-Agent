package com.meetingai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingai.exception.AiServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around Google Gemini's OpenAI-compatible REST endpoint.
 * Uses the free-tier Gemini API key from {@code aistudio.google.com}
 * (configured via {@code gemini.api-key}). Translation tasks use this
 * instead of the local Ollama model because Gemini's multilingual quality
 * is better for on-demand translation.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gemini.base-url}")
    private String baseUrl;

    @Value("${gemini.api-key}")
    private String apiKey;

    /**
     * Sends a single user message to the given model and returns the raw
     * text response. Uses a low temperature so translation stays faithful.
     */
    public String generate(String model, String prompt) {
        String url = baseUrl + "/chat/completions";
        log.info("[Gemini] Calling {} with model='{}', prompt length={} chars", url, model, prompt.length());

        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            long start = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            long elapsedMs = System.currentTimeMillis() - start;

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            String result = content.isTextual() ? content.asText() : "";

            log.info("[Gemini] Response received in {} ms, length={} chars", elapsedMs, result.length());
            log.debug("[Gemini] Raw response text:\n{}", result);
            return result;
        } catch (Exception e) {
            log.error("[Gemini] Call to {} failed: {}", url, e.getMessage(), e);
            throw new AiServiceUnavailableException(
                    "The translation service isn't reachable right now. Check your GEMINI_API_KEY and internet connection, then try again.", e);
        }
    }
}
