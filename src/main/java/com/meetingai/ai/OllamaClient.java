package com.meetingai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Thin wrapper around Ollama's local REST API (default: http://localhost:11434).
 * Ollama must be running locally with the target model already pulled
 * (e.g. `ollama pull llama3.1:8b`) before this will work.
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ollama.base-url:http://:11434}") // to change - server
    private String baseUrl;

    /**
     * Sends a prompt to the given model and returns the raw text response.
     * Uses stream:false so the full response comes back in one call
     * (simplest option — no need to handle chunked streaming yet).
     */
    public String generate(String model, String prompt) {
        String url = baseUrl + "/api/generate";
        log.info("[Ollama] Calling {} with model='{}', prompt length={} chars", url, model, prompt.length());

        Map<String, Object> body = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            long start = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            long elapsedMs = System.currentTimeMillis() - start;

            JsonNode root = objectMapper.readTree(response.getBody());
            String result = root.get("response").asText();

            log.info("[Ollama] Response received in {} ms, length={} chars", elapsedMs, result.length());
            log.debug("[Ollama] Raw response text:\n{}", result);
            return result;
        } catch (Exception e) {
            log.error("[Ollama] Call to {} failed: {}", url, e.getMessage(), e);
            throw new RuntimeException("Failed to call Ollama at " + url + ": " + e.getMessage(), e);
        }
    }
}