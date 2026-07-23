package com.meetingai.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Audit record of the post-processing reminder (calendar event(s) + email)
 * sent for a completed meeting. One row per meeting — created once the
 * async pipeline finishes analyzing a meeting and attempts to notify the
 * user via their connected Google account.
 *
 * This exists mainly for:
 * - Idempotency: don't re-send if the pipeline is ever re-triggered.
 * - Debuggability: when Google isn't connected, or a send fails (expired
 *   refresh token, missing scope, etc.), the failure is recorded here
 *   instead of only living in a log line.
 */
@Entity
@Table(
        name = "reminders",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reminder_meeting",
                        columnNames = "meeting_id"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The meeting this reminder was generated for.
     * One meeting has at most one reminder record.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "meeting_id",
            nullable = false,
            unique = true
    )
    private Meeting meeting;

    /**
     * Whether the reminder step ran at all — false when the user
     * simply hasn't connected Google, which is an expected, non-error
     * state, not a failure.
     */
    @Column(name = "google_connected", nullable = false)
    @Builder.Default
    private boolean googleConnected = false;

    /**
     * Whether the summary/action-items email was sent successfully.
     */
    @Column(name = "email_sent", nullable = false)
    @Builder.Default
    private boolean emailSent = false;

    @Lob
    @Column(name = "email_error", columnDefinition = "TEXT")
    private String emailError;

    /**
     * How many Google Calendar events were successfully created for
     * this meeting (the general recap event, plus one per action item
     * with a deadline that could be parsed into an actual date).
     */
    @Column(name = "calendar_events_created", nullable = false)
    @Builder.Default
    private int calendarEventsCreated = 0;

    @Lob
    @Column(name = "calendar_error", columnDefinition = "TEXT")
    private String calendarError;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
