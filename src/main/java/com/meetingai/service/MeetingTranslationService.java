package com.meetingai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingai.ai.GeminiClient;
import com.meetingai.dto.ActionItem;
import com.meetingai.dto.MeetingAnalysisResult;
import com.meetingai.dto.Topic;
import com.meetingai.dto.TranslateMeetingRequest;
import com.meetingai.dto.TranslatedMeetingResponse;
import com.meetingai.entity.Meeting;
import com.meetingai.entity.MeetingStatus;
import com.meetingai.exception.AiServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Translates a completed meeting's analysis (summary, topics, action items,
 * decisions, open questions) into a requested language using Google Gemini.
 * The source content is the meeting's already-stored structured analysis —
 * nothing new is persisted, the translated copy is returned to the UI to
 * render below the original.
 */
@Service
public class MeetingTranslationService {

    private static final Logger log = LoggerFactory.getLogger(MeetingTranslationService.class);

    /** Languages Gemini is prompted to support, matching what aya:8b handles natively. */
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "English", "Hindi", "Tamil", "Telugu", "Kannada", "Malayalam", "Marathi",
            "Bengali", "Gujarati", "Punjabi", "Urdu", "Spanish", "French", "German",
            "Italian", "Portuguese", "Japanese", "Korean", "Chinese", "Arabic", "Russian",
            "Indonesian", "Vietnamese", "Thai", "Turkish", "Dutch"
    );

    private final MeetingService meetingService;
    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gemini.model}")
    private String model;

    public MeetingTranslationService(MeetingService meetingService, GeminiClient geminiClient) {
        this.meetingService = meetingService;
        this.geminiClient = geminiClient;
    }

    /** Full supported-language list, so the frontend dropdown and backend stay in sync. */
    public static Set<String> supportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    public TranslatedMeetingResponse translate(Long meetingId, TranslateMeetingRequest request) {
        Meeting meeting = meetingService.getOwnedMeetingEntity(meetingId);
        requireCompleted(meeting);

        String targetLanguage = normalizeLanguage(request.getTargetLanguage());
        if (targetLanguage == null) {
            throw new IllegalArgumentException(
                    "Unsupported language '" + request.getTargetLanguage() + "'. Supported languages: "
                            + String.join(", ", SUPPORTED_LANGUAGES.stream().sorted().toList()));
        }

        SourceEmptyState empty = sourceEmptyState(meeting);
        String sourceJson = buildSourceJson(meeting);
        String prompt = buildPrompt(targetLanguage, sourceJson);
        log.info("[Translate] meetingId={} — asking Gemini to translate into {}, source length={} chars",
                meetingId, targetLanguage, sourceJson.length());

        String raw;
        try {
            raw = geminiClient.generate(model, prompt);
        } catch (AiServiceUnavailableException e) {
            throw e;
        }

        return parseTranslated(raw, meetingId, targetLanguage, empty);
    }

    private void requireCompleted(Meeting meeting) {
        if (meeting.getStatus() != MeetingStatus.COMPLETED) {
            throw new IllegalArgumentException(
                    "Meeting has not finished processing yet");
        }
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String trimmed = language.trim();
        return SUPPORTED_LANGUAGES.stream()
                .filter(lang -> lang.equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }

    /**
     * Rebuilds the analysis as a clean JSON object with the same keys the
     * LLM originally produced, so the translation prompt only has to change
     * values, never structure. Empty source fields stay empty ("" or []) so
     * the response can never invent content the meeting doesn't have.
     */
    private String buildSourceJson(Meeting meeting) {
        MeetingAnalysisResult result = new MeetingAnalysisResult();
        result.setSummary(meeting.getSummary() != null ? meeting.getSummary() : "");
        result.setTopics(parseList(meeting.getTopicsJson(), Topic.class));
        result.setActionItems(parseList(meeting.getActionItemsJson(), ActionItem.class));
        result.setDecisions(parseList(meeting.getDecisionsJson(), String.class));
        result.setOpenQuestions(parseList(meeting.getOpenQuestionsJson(), String.class));

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("summary", result.getSummary());
        source.put("topics", result.getTopics());
        source.put("action_items", result.getActionItems());
        source.put("decisions", result.getDecisions());
        source.put("open_questions", result.getOpenQuestions());

        try {
            return objectMapper.writeValueAsString(source);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize meeting analysis for translation", e);
        }
    }

    /** Which source fields are empty — used to force the same emptiness on the response. */
    private SourceEmptyState sourceEmptyState(Meeting meeting) {
        return new SourceEmptyState(
                meeting.getSummary() == null || meeting.getSummary().isBlank(),
                isBlankList(meeting.getTopicsJson()),
                isBlankList(meeting.getActionItemsJson()),
                isBlankList(meeting.getDecisionsJson()),
                isBlankList(meeting.getOpenQuestionsJson())
        );
    }

    private boolean isBlankList(String json) {
        return json == null || json.isBlank() || "[]".equals(json.trim());
    }

    private <T> List<T> parseList(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (IOException e) {
            log.warn("[Translate] Failed to parse stored JSON for {}, using empty list: {}",
                    type.getSimpleName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private String buildPrompt(String targetLanguage, String sourceJson) {
        return "You are a professional translator. Translate the meeting analysis JSON below into "
                + targetLanguage + ".\n\n"
                + "Rules:\n"
                + "- Keep the EXACT same JSON structure and keys — only translate the values.\n"
                + "- Translate every textual value into " + targetLanguage + " faithfully; do not transliterate.\n"
                + "- Keep proper names (people, companies, products, project names) exactly as they are.\n"
                + "- Keep deadlines, dates, and numbers clear and unchanged in meaning.\n"
                + "- Keep standard technical terms (API, REST, GraphQL, sprint, PR, merge, branch, repo, deploy, "
                + "staging, production, bug, ticket, endpoint, database, frontend, backend, latency, CI/CD) in their "
                + "standard English form.\n"
                + "- Do not add, remove, or paraphrase anything beyond the translation.\n"
                + "- If a list is empty, keep it an empty array [].\n"
                + "- Return ONLY the translated JSON, with no extra text, no explanations, and no markdown code fences.\n\n"
                + "SOURCE JSON:\n" + sourceJson;
    }

    private TranslatedMeetingResponse parseTranslated(String raw, Long meetingId, String targetLanguage,
                                                      SourceEmptyState empty) {
        String cleaned = stripCodeFences(raw);

        try {
            JsonNode root = objectMapper.readTree(cleaned);
            if (root == null || !root.isObject()) {
                throw new AiServiceUnavailableException(
                        "The translation model returned an unparseable response — please try again.", null);
            }

            return TranslatedMeetingResponse.builder()
                    .meetingId(meetingId)
                    .targetLanguage(targetLanguage)
                    .summary(empty.summary ? "" : root.path("summary").asText())
                    .topics(empty.topics ? Collections.emptyList() : readList(root, "topics", Topic.class))
                    .actionItems(empty.actionItems ? Collections.emptyList()
                            : readList(root, "action_items", ActionItem.class))
                    .decisions(empty.decisions ? Collections.emptyList() : readStringList(root, "decisions"))
                    .openQuestions(empty.openQuestions ? Collections.emptyList()
                            : readStringList(root, "open_questions"))
                    .build();
        } catch (IOException e) {
            log.error("[Translate] meetingId={} — failed to parse Gemini response: {}", meetingId, e.getMessage());
            throw new AiServiceUnavailableException(
                    "The translation model returned an unparseable response — please try again.", e);
        }
    }

    private <T> List<T> readList(JsonNode root, String field, Class<T> type) {
        JsonNode node = root.path(field);
        if (!node.isArray()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(node.toString(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (IOException e) {
            log.warn("[Translate] Failed to parse '{}' from response, using empty list: {}", field, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> readStringList(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (!node.isArray()) {
            return Collections.emptyList();
        }
        List<String> values = new java.util.ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual()) {
                values.add(item.asText());
            }
        });
        return values;
    }

    private String stripCodeFences(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "");
            s = s.replaceFirst("\\s*```$", "");
            s = s.trim();
        }
        return s;
    }

    /** Tracks which source fields were empty, so the response mirrors them. */
    private record SourceEmptyState(boolean summary, boolean topics, boolean actionItems,
                                    boolean decisions, boolean openQuestions) {
    }
}
