package com.meetingai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Transcription service using faster-whisper (via Python subprocess).
 *
 * Pipeline for every uploaded file:
 *   1. If video  → ffmpeg extracts audio track to WAV
 *   2. ffmpeg    → denoise, normalise, convert to 16 kHz mono WAV
 *   3. faster-whisper (large-v3-turbo, int8) → transcript + detected language
 *
 * Why faster-whisper instead of the original whisper CLI?
 *   - ~7x faster on CPU for the same model size
 *   - large-v3-turbo (809M params) matches large-v3 accuracy at medium speed
 *   - int8 quantization fits in 8 GB RAM alongside Spring Boot + Aya
 *   - vad_filter removes silence → no more <|ru|> hallucinations
 *
 * Requires:
 *   pip install faster-whisper
 *   ffmpeg on PATH
 *   faster_whisper_transcribe.py in the project root (or configured via
 *   whisper.script-path in application.yml)
 */
@Service
public class WhisperService {

    private static final Logger log = LoggerFactory.getLogger(WhisperService.class);

    // -------------------------------------------------------------------------
    // Supported file extensions
    // -------------------------------------------------------------------------

    private static final Set<String> AUDIO_EXTENSIONS =
            Set.of(".mp3", ".wav", ".m4a", ".ogg", ".flac", ".aac", ".wma", ".opus");

    private static final Set<String> VIDEO_EXTENSIONS =
            Set.of(".mp4", ".mov", ".avi", ".mkv", ".webm", ".flv", ".wmv",
                    ".ts", ".mpeg", ".mpg");

    // -------------------------------------------------------------------------
    // Configuration — application.yml
    // -------------------------------------------------------------------------

    // Model to use. Recommended: large-v3-turbo (best speed/accuracy on CPU)
    // Options: tiny, base, small, medium, large-v2, large-v3, large-v3-turbo
    @Value("${whisper.model:large-v3-turbo}")
    private String whisperModel;

    // ISO 639-1 language code ("ta", "en", "hi") or "auto" for auto-detect.
    // Explicit codes prevent language misdetection on noisy audio.
    @Value("${whisper.language:auto}")
    private String whisperLanguage;

    // Path to faster_whisper_transcribe.py — default: project root.
    @Value("${whisper.script-path:faster_whisper_transcribe.py}")
    private String whisperScriptPath;

    // Optional space-separated list of domain-specific terms (e.g. "API
    // REST GraphQL sprint backlog"), externalized to application.yml so
    // it's easy to tune. Passed to faster_whisper_transcribe.py as an
    // additional decoder prompt hint — helps it keep known English
    // loanwords in Latin script instead of phonetically transliterating
    // them into the transcript's main language.
    @Value("${whisper.vocabulary-hint:}")
    private String vocabularyHint;

    private static final Path OUTPUT_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "whisper-output");

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Transcribes using whisperLanguage from application.yml. */
    public TranscriptionResult transcribe(String filePath) {
        return transcribe(filePath, whisperLanguage);
    }

    /**
     * Transcribes with an explicit language hint.
     * Pass "auto" to detect, or ISO 639-1 code ("ta", "en", "hi") to force.
     */
    public TranscriptionResult transcribe(String filePath, String languageHint) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            log.info("[Whisper] Pipeline start. model={}, language={}, file={}",
                    whisperModel, languageHint, filePath);

            // Step 1 — extract audio if video
            String audioPath = extractAudioIfVideo(filePath);

            // Step 2 — denoise, normalise, 16 kHz mono WAV
            String cleanedPath = preprocessAudio(audioPath);

            // Step 3 — faster-whisper transcription
            return runFasterWhisper(cleanedPath, languageHint);

        } catch (IOException | InterruptedException e) {
            log.error("[Whisper] Pipeline failed: {}", e.getMessage(), e);
            throw new RuntimeException("Whisper transcription failed: " + e.getMessage(), e);
        }
    }

    /** Returns true if the filename is a supported audio or video file. */
    public static boolean isSupportedFile(String filename) {
        if (filename == null) return false;
        String ext = getExtension(filename).toLowerCase();
        return AUDIO_EXTENSIONS.contains(ext) || VIDEO_EXTENSIONS.contains(ext);
    }

    /** Returns true if the filename is a video file. */
    public static boolean isVideoFile(String filename) {
        if (filename == null) return false;
        return VIDEO_EXTENSIONS.contains(getExtension(filename).toLowerCase());
    }

    /** Generates a unique temp file path for a newly uploaded file. */
    public static String generateTempFilePath(String originalFilename) {
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.'))
                : ".wav";
        Path tempFile = Path.of(System.getProperty("java.io.tmpdir"),
                "meeting-upload-" + UUID.randomUUID() + extension);
        return tempFile.toString();
    }

    // -------------------------------------------------------------------------
    // Step 1 — Video → Audio extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts audio from video using ffmpeg.
     * Audio files pass through unchanged.
     *
     * ffmpeg flags:
     *   -vn        strip video stream
     *   -ar 16000  16 kHz sample rate (faster-whisper native rate)
     *   -ac 1      mono channel
     */
    private String extractAudioIfVideo(String filePath)
            throws IOException, InterruptedException {

        if (!isVideoFile(filePath)) {
            log.info("[ffmpeg] Not a video file, skipping extraction. file={}", filePath);
            return filePath;
        }

        String extractedPath = tempPath("extracted", ".wav");
        log.info("[ffmpeg] Extracting audio from video. input={}, output={}",
                filePath, extractedPath);

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", filePath,
                "-vn", "-ar", "16000", "-ac", "1",
                "-y", extractedPath
        );
        pb.redirectErrorStream(true);

        int exitCode = runProcess(pb, "[ffmpeg:extract]");
        if (exitCode != 0) {
            log.warn("[ffmpeg] Audio extraction failed (exit {}), using original file.", exitCode);
            return filePath;
        }

        log.info("[ffmpeg] Audio extracted. output={}", extractedPath);
        return extractedPath;
    }

    // -------------------------------------------------------------------------
    // Step 2 — Audio preprocessing
    // -------------------------------------------------------------------------

    /**
     * Preprocesses audio using ffmpeg filter chain.
     * Improves faster-whisper accuracy on noisy meeting recordings.
     *
     * Filter chain:
     *   highpass=f=200   removes low-frequency rumble (AC hum, table knocks)
     *   lowpass=f=3000   removes high-frequency hiss above speech range
     *   loudnorm         normalises volume so all speakers are even
     *   afftdn=nf=-25    AI noise reduction built into ffmpeg
     *
     * Output: 16 kHz mono WAV.
     * Falls back to input file if preprocessing fails.
     */
    private String preprocessAudio(String inputPath)
            throws IOException, InterruptedException {

        String outputPath = tempPath("clean", ".wav");
        log.info("[ffmpeg] Preprocessing audio. input={}, output={}", inputPath, outputPath);

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", inputPath,
                "-af", "highpass=f=200,lowpass=f=3000,loudnorm,afftdn=nf=-25",
                "-ar", "16000", "-ac", "1",
                "-y", outputPath
        );
        pb.redirectErrorStream(true);

        int exitCode = runProcess(pb, "[ffmpeg:preprocess]");
        if (exitCode != 0) {
            log.warn("[ffmpeg] Preprocessing failed (exit {}), falling back to input file.", exitCode);
            return inputPath;
        }

        log.info("[ffmpeg] Preprocessing done. output={}", outputPath);
        return outputPath;
    }

    // -------------------------------------------------------------------------
    // Step 3 — faster-whisper via Python script
    // -------------------------------------------------------------------------

    /**
     * Calls faster_whisper_transcribe.py as a Python subprocess and parses
     * the JSON output into a TranscriptionResult.
     *
     * Script prints a single JSON line to stdout:
     *   {"text": "...", "language": "ta", "language_probability": 0.9921}
     *
     * stderr is read in a background thread to prevent deadlock if the
     * stderr buffer fills up while we are reading stdout.
     */
    private TranscriptionResult runFasterWhisper(String audioFilePath, String languageHint)
            throws IOException, InterruptedException {

        log.info("[faster-whisper] Transcribing. model={}, language={}, file={}",
                whisperModel, languageHint, audioFilePath);

        List<String> cmd = new ArrayList<>(List.of(
                "python",            // Windows: always "python" not "python3"
                whisperScriptPath,
                audioFilePath,
                whisperModel,
                languageHint != null ? languageHint : "auto",
                vocabularyHint != null ? vocabularyHint : ""
        ));

        ProcessBuilder pb = new ProcessBuilder(cmd);

        // Critical for Tamil/Hindi/non-Latin output on Windows:
        // Without UTF-8 override, Python's print() crashes on non-ASCII
        // characters, killing the process before the JSON is written.
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");

        // Keep stdout and stderr separate so we can parse stdout cleanly.
        pb.redirectErrorStream(false);

        Process process = pb.start();

        // Read stderr in a background thread — prevents deadlock if the
        // stderr buffer fills while we block reading stdout.
        StringBuilder stderrOutput = new StringBuilder();
        Thread stderrThread = new Thread(() -> {
            try (var reader = process.errorReader(java.nio.charset.StandardCharsets.UTF_8)) {
                reader.lines().forEach(line -> {
                    stderrOutput.append(line).append(System.lineSeparator());
                    log.debug("[faster-whisper stderr] {}", line);
                });
            } catch (IOException e) {
                log.warn("[faster-whisper] Error reading stderr: {}", e.getMessage());
            }
        });
        stderrThread.start();

        // Read stdout — should be exactly one JSON line
        StringBuilder stdoutOutput = new StringBuilder();
        try (var reader = process.inputReader(java.nio.charset.StandardCharsets.UTF_8)) {
            reader.lines().forEach(line -> {
                stdoutOutput.append(line);
                log.debug("[faster-whisper stdout] {}", line);
            });
        }

        int exitCode = process.waitFor();
        stderrThread.join();

        log.info("[faster-whisper] Process exited with code {}", exitCode);

        if (exitCode != 0) {
            throw new RuntimeException(
                    "faster-whisper exited with code " + exitCode
                            + ".\nStderr: " + stderrOutput);
        }

        String jsonOutput = stdoutOutput.toString().trim();
        if (jsonOutput.isEmpty()) {
            throw new RuntimeException(
                    "faster-whisper produced no output.\nStderr: " + stderrOutput);
        }

        JsonNode root = objectMapper.readTree(jsonOutput);

        if (root.has("error")) {
            throw new RuntimeException(
                    "faster-whisper script error: " + root.get("error").asText());
        }

        String text     = root.has("text")     ? root.get("text").asText().trim() : "";
        String language = root.has("language") ? root.get("language").asText()    : "unknown";
        double langProb = root.has("language_probability")
                ? root.get("language_probability").asDouble() : 0.0;

        log.info("[faster-whisper] Done. detectedLanguage='{}' (confidence={:.0f}%), transcriptLength={} chars",
                language, langProb * 100, text.length());

        return new TranscriptionResult(text, language);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Runs a ProcessBuilder, streams output to DEBUG logs, returns exit code. */
    private int runProcess(ProcessBuilder pb, String logPrefix)
            throws IOException, InterruptedException {
        Process process = pb.start();
        try (var reader = process.inputReader(java.nio.charset.StandardCharsets.UTF_8)) {
            reader.lines().forEach(line -> log.debug("{} {}", logPrefix, line));
        }
        return process.waitFor();
    }

    /** Returns a unique temp file path with the given suffix. */
    private static String tempPath(String label, String extension) {
        return Path.of(System.getProperty("java.io.tmpdir"),
                "meeting-" + label + "-" + UUID.randomUUID() + extension).toString();
    }

    /** Returns the file extension including the dot, e.g. ".mp4". */
    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}