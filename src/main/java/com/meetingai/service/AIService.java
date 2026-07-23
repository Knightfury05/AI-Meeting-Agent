package com.meetingai.service;

import com.meetingai.ai.OllamaClient;
import com.meetingai.entity.Meeting;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AIService {

    private final OllamaClient ollamaClient;

    private static final String MODEL = "aya:8b"; //model change

    /**
     * Optional domain/vocabulary primer, externalized to application.yml
     * (app.ai.domain-context) so it's easy to tune without a redeploy.
     *
     * Local models like Aya have no memory of your team, so without this
     * they have to guess at technical vocabulary from context alone — which
     * is exactly how a mangled transcript turns into hallucinated names
     * instead of recognizing "API" as English. This gets prepended to
     * every prompt, before the transcript, so the model knows what kind
     * of meeting it's reading before it starts.
     */
    @Value("${app.ai.domain-context:}")
    private String domainContext;

    public AIService(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }


    public String analyzeMeeting(Meeting meeting) {

        String languageName = languageNameFor(meeting.getDetectedLanguage());
        String outputLanguage = (meeting.getOutputLanguage() != null && !meeting.getOutputLanguage().isBlank())
                ? meeting.getOutputLanguage()
                : "English";

        String contextBlock =
                (domainContext != null && !domainContext.isBlank())
                        ? "CONTEXT ABOUT THESE MEETINGS:\n" + domainContext.trim() + "\n\n"
                        : "";

        String prompt =
                contextBlock +
                        "You are a meeting assistant.\n\n" +
                        "The meeting transcript below is in " + languageName + ". " +
                        "Write your ENTIRE response — summary, topic titles, topic discussion notes, " +
                        "action item tasks, decisions, and open questions — in " + outputLanguage + ", regardless of what " +
                        "language the transcript is in. Translate the meaning faithfully; do not transliterate " +
                        "or leave any words in the output that are not in " + outputLanguage + ". Keep proper names (people, products, " +
                        "companies) as they are, and convert dates/numbers to a clear format.\n\n" +
                        "Return ONLY valid JSON in this exact format, with no extra text before or after it:\n" +
                        "{\n" +
                        "  \"summary\": \"3-5 sentence overall summary\",\n" +
                        "  \"participants\": [\n" +
                        "    {\"name\": \"name of a person present in or directly addressed by the meeting\", \"role\": \"their job title or team if mentioned, otherwise ''\"}\n" +
                        "  ],\n" +
                        "  \"topics\": [\n" +
                        "    {\"title\": \"short topic name, e.g. 'API Redesign'\", \"discussion\": \"1-2 sentences on what was said about this topic\"}\n" +
                        "  ],\n" +
                        "  \"action_items\": [\n" +
                        "    {\"owner\": \"name, role, or 'team' if no specific person is named\", \"task\": \"description of the task\", \"deadline\": \"date if mentioned, otherwise null\"}\n" +
                        "  ],\n" +
                        "  \"decisions\": [\"a decision or agreement that was made\"],\n" +
                        "  \"open_questions\": [\"a question that was raised but not resolved\"]\n" +
                        "}\n\n" +
                        "RULES FOR PARTICIPANTS:\n" +
                        "- List every distinct person who is named as speaking, being addressed, or being " +
                        "referred to as present in the meeting (e.g. introductions, someone saying 'this is X', " +
                        "someone being asked a direct question, or a speaker signing off with their own name).\n" +
                        "- Use the exact name as it appears in the transcript. Do not invent names, and do not " +
                        "list generic roles like 'the team' or 'everyone' as a participant — only actual named people.\n" +
                        "- Deduplicate: each person should appear once even if mentioned multiple times.\n" +
                        "- If the transcript never names anyone specifically, return an empty array [] rather than " +
                        "guessing.\n" +
                        "- Never include an email address — transcripts don't contain them, so leave that out " +
                        "entirely; only 'name' and 'role' belong here.\n\n" +
                        "RULES FOR TOPICS:\n" +
                        "- Break the meeting into distinct discussion points or agenda items, the way a real Minutes " +
                        "of Meeting (MOM) document would list them — for example 'API Redesign', 'Crash Investigation', " +
                        "'Sprint Retro'.\n" +
                        "- Give each topic a short title (2-5 words) and a brief discussion note.\n" +
                        "- If the meeting only covers one subject overall, it is fine to return a single topic.\n" +
                        "- If the transcript is too short or vague to identify distinct topics, return an empty " +
                        "array [] rather than inventing topics that were not actually discussed.\n\n" +
                        "RULES FOR ACTION ITEMS:\n" +
                        "- An action item is anything the transcript asks a person, role, or the whole team to do — " +
                        "including instructions addressed to 'the team' or 'everyone', not just named individuals.\n" +
                        "- Requests, reminders, and instructions all count as action items, even if phrased politely " +
                        "or indirectly (e.g. 'please review the documents' or 'kindly inform the manager' are action items).\n" +
                        "- If no specific person is named, use \"team\" as the owner instead of \"unknown\".\n" +
                        "- A transcript can easily have 1-3 action items even if it is short and informal — do not " +
                        "default to an empty list just because the tone is casual or no names are mentioned.\n\n" +
                        "RULES FOR DECISIONS AND OPEN QUESTIONS:\n" +
                        "- Only include a decision if something was explicitly decided, scheduled, or agreed upon.\n" +
                        "- Only include an open question if something was genuinely asked and left unresolved.\n" +
                        "- It is normal and expected for decisions or open_questions to be empty arrays [] when a " +
                        "transcript is just an announcement or reminder with no back-and-forth discussion.\n\n" +
                        "GENERAL RULES:\n" +
                        "- Never invent placeholder entries. Never return an array containing only empty strings " +
                        "or objects with blank fields. If a category truly has nothing, return [].\n" +
                        "- Every value in the JSON must be in " + outputLanguage + ", no matter what language the transcript was " +
                        "originally in.\n\n" +
                        "EXAMPLE — for the transcript \"Reminder: the review meeting is Friday at 2pm. Please read " +
                        "the design doc beforehand. Let the manager know by Thursday if you can't make it.\", " +
                        "a correct response (in " + outputLanguage + ") would be:\n" +
                        "{\n" +
                        "  \"summary\": \"A reminder that the review meeting is scheduled for Friday at 2pm, with a request to read the design doc in advance and notify the manager of any absence by Thursday.\",\n" +
                        "  \"participants\": [],\n" +
                        "  \"topics\": [\n" +
                        "    {\"title\": \"Review Meeting Logistics\", \"discussion\": \"The team was reminded of the Friday 2pm review meeting and asked to read the design doc beforehand.\"}\n" +
                        "  ],\n" +
                        "  \"action_items\": [\n" +
                        "    {\"owner\": \"team\", \"task\": \"Read the design doc before the meeting\", \"deadline\": \"Friday 2pm\"},\n" +
                        "    {\"owner\": \"team\", \"task\": \"Notify the manager if unable to attend\", \"deadline\": \"Thursday\"}\n" +
                        "  ],\n" +
                        "  \"decisions\": [],\n" +
                        "  \"open_questions\": []\n" +
                        "}\n\n" +
                        "If the transcript above were in a different language (e.g. Tamil, Hindi, Spanish), the " +
                        "response would look exactly the same — fully in " + outputLanguage + ".\n\n" +
                        "Now analyze this actual transcript and respond entirely in " + outputLanguage + ":\n\n" +
                        "TRANSCRIPT:\n" +
                        meeting.getTranscript();

        return ollamaClient.generate(MODEL, prompt);
    }

    /**
     * Maps Whisper's ISO 639-1 language codes to readable names for the prompt.
     * Models follow "respond in Tamil" far more reliably than "respond in ta".
     * Falls back to the raw code (or English) if not in this lookup, which
     * still works reasonably since most codes are recognizable on their own.
     */
    private String languageNameFor(String isoCode) {
        if (isoCode == null || isoCode.isBlank() || isoCode.equals("unknown")) {
            return "English";
        }
        return switch (isoCode.toLowerCase()) {
            case "en" -> "English";
            case "hi" -> "Hindi";
            case "ta" -> "Tamil";
            case "te" -> "Telugu";
            case "kn" -> "Kannada";
            case "ml" -> "Malayalam";
            case "mr" -> "Marathi";
            case "bn" -> "Bengali";
            case "gu" -> "Gujarati";
            case "pa" -> "Punjabi";
            case "ur" -> "Urdu";
            case "es" -> "Spanish";
            case "fr" -> "French";
            case "de" -> "German";
            case "it" -> "Italian";
            case "pt" -> "Portuguese";
            case "ja" -> "Japanese";
            case "ko" -> "Korean";
            case "zh" -> "Chinese";
            case "ar" -> "Arabic";
            case "ru" -> "Russian";
            case "id" -> "Indonesian";
            case "vi" -> "Vietnamese";
            case "th" -> "Thai";
            case "tr" -> "Turkish";
            case "nl" -> "Dutch";
            default -> isoCode; // unrecognized code — pass through as-is
        };
    }
}