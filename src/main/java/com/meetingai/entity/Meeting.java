package com.meetingai.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "meetings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Meeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owner of this meeting. EAGER would be simpler to use everywhere, but
    * LAZY is correct here: list endpoints (getAll) don't need the full User
    * loaded for every row, just the id for the ownership check, and the
    * service layer that does need user.getEmail()/getName() already runs
    * inside a transaction where lazy-loading works fine.
    */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    // Path to the saved audio file on disk (temp or permanent storage)
    @Column(name = "audio_file_path")
    private String audioFilePath;

    // Full transcript produced by Whisper
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String transcript;

    // ISO 639-1 language code Whisper auto-detected, e.g. "en", "hi", "ta"
    @Column(name = "detected_language")
    private String detectedLanguage;

    /** Language the user wants the AI output (summary, action items, etc.) written in.
    * Stored as a readable name, e.g. "English", "Tamil", "Hindi".
    * Defaults to "English" when not supplied by the caller.
    */

    @Column(name = "output_language", nullable = false)
    @Builder.Default
    private String outputLanguage = "English";

    // Summary text produced by the LLM
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String summary;

    /** Action items, decisions, open questions, topics — stored as raw JSON text.
    * Kept as a JSON string for now (simplest option); see notes below
    * for the alternative of normalizing into a separate table later.
    */
    @Lob
    @Column(name = "participants_json", columnDefinition = "LONGTEXT")
    private String participantsJson;

    @Lob
    @Column(name = "topics_json", columnDefinition = "LONGTEXT")
    private String topicsJson;

    @Lob
    @Column(name = "action_items_json", columnDefinition = "LONGTEXT")
    private String actionItemsJson;

    @Lob
    @Column(name = "decisions_json", columnDefinition = "LONGTEXT")
    private String decisionsJson;

    @Lob
    @Column(name = "open_questions_json", columnDefinition = "LONGTEXT")
    private String openQuestionsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MeetingStatus status = MeetingStatus.PENDING;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}