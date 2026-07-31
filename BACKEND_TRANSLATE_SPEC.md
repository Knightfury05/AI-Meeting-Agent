# Backend AI — Implement Meeting Translation Feature

**Project:** Spring Boot service (server.port 8081), Java, JWT auth via `SecurityFilter`/`GlobalExceptionHandler`. Existing routes live in a `MeetingController` under `/api/meetings`. You already have an LLM integration used by the summary pipeline (Ollama/Gemini) — reuse the same call path for translation. Do not touch CORS (already allows the frontend origin).

Add **two authenticated endpoints**. Both are read-only/on-demand — they must never mutate the stored `Meeting` record. Keep the existing `{ "message": "..." }` error envelope.

---

## 1) `GET /api/meetings/languages`

Returns the supported languages as a plain JSON array.

```
200 → ["Arabic","Bengali","Chinese","Dutch","English","French","German","Gujarati","Hindi","Indonesian","Italian","Japanese","Kannada","Korean","Malayalam","Marathi","Punjabi","Portuguese","Russian","Spanish","Tamil","Telugu","Thai","Turkish","Urdu","Vietnamese"]
```

Define these 26 names as a constant (they also power upload-time `outputLanguage`). If you already have a language list for `analyze`, share it between both — the translation list must be the same set.

---

## 2) `POST /api/meetings/{id}/translate`

**Request body:**
```json
{ "targetLanguage": "Tamil" }
```
`targetLanguage` = full human-readable name (case-insensitive), NOT a code. Normalize (trim, compare ignoring case) against the constant list.

**Processing order (important for correct status codes):**
1. Load meeting by `{id}`, verify it belongs to the authenticated user → else throw `404` ("Meeting not found").
2. `if (meeting.status != "COMPLETED")` → throw `400` ("Meeting has not finished processing yet").
3. Validate `targetLanguage` is supported → else `400` ("Unsupported language: X").
4. Translate each analysis field via the LLM service (single prompt, or one call per field — either is fine). Do NOT write anything back to the DB.

**200 response — field names MUST match `GET /api/meetings/{id}`:**
```json
{
  "meetingId": 5,
  "targetLanguage": "Tamil",
  "summary": "…",
  "topics":      [ { "title": "…", "discussion": "…" } ],
  "actionItems": [ { "owner": "…", "task": "…", "deadline": "…" } ],
  "decisions":   [ "…" ],
  "openQuestions": [ "…" ]
}
```

**Field mapping from the entity:**
- `summary` ← translated `meeting.summary`
- `topics` ← translated `meeting.topics`, preserving each object's `title`/`discussion`
- `actionItems` ← translated `meeting.actionItems`, preserving `owner`/`task`/`deadline`
- `decisions` ← translated list of decision strings
- `openQuestions` ← translated list of question strings

**Empty-field rule:** if a source field is `[]` or empty string, the response field must also be empty. Never invent placeholders, never silently fall back to English.

**Errors (via `GlobalExceptionHandler`, shape `{ "message": "..." }`):**
| Code | Condition |
|------|-----------|
| 400 | unsupported language, or meeting not COMPLETED |
| 404 | meeting not found / not owned by caller |
| 503 | translation service unreachable / upstream failure |

---

## Suggested implementation shape (if useful)

- `MeetingController`: add the two routes next to the existing `/api/meetings/**` handlers.
- `TranslationService` (new bean): wraps the existing LLM client, exposes `List<String> supportedLanguages()` and `TranslationResult translate(Meeting m, String lang)`; map upstream failures to a `503` via the existing exception hierarchy.
- `TranslationResult` DTO with the five fields above; build `topics`/`actionItems` from the entity's existing `Topic`/`ActionItem` DTO structures so field names stay identical.
- Reuse your current `@PreAuthorize`/ownership check pattern from `GET /api/meetings/{id}`.

## Non-functional

- Response within ~1–3 s (free-tier LLM); frontend shows a spinner meanwhile, so keep it synchronous — no polling contract.
- No caching, no persistence, no DB writes, no email/calendar side effects.
- Only the JWT chain; regular (non-admin) users translate their own meetings.

## Verify

Restart the backend, then with a `Bearer` token:
- `GET /api/meetings/languages` → 26-name array.
- `POST /api/meetings/{id}/translate` with `{"targetLanguage":"Tamil"}` on a COMPLETED meeting → 200 with all five fields translated; the stored meeting unchanged (`GET /api/meetings/{id}` still returns original language).
- Bad cases: `"Klingon"` → 400; a PENDING meeting → 400; another user's id → 404.

---

**Frontend contract (don't break):** `src/api.js` calls `GET /api/meetings/languages` and `POST /api/meetings/{id}/translate` with `{ targetLanguage }`. It reads `summary/topics/actionItems/decisions/openQuestions` and shows the backend's `message` on error. If `/languages` is unreachable the UI falls back to the hardcoded list, but translate will 404 until this route is live.
