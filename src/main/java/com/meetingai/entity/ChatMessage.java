package com.meetingai.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One turn in the Q&A thread attached to a meeting (either the user's
 * question or the AI's answer). Kept as flat rows rather than a JSON blob
 * on Meeting so the conversation can grow without rewriting the whole
 * history on every message, and so it can be queried/paginated later if
 * a meeting accumulates a long thread.
 */
@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sender sender;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public enum Sender {
        USER, AI
    }
}
