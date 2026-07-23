package com.meetingai.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "google_connections",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_google_connection_user",
                        columnNames = "user_id"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoogleConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The PixelGenius user who owns this Google connection.
     *
     * One PixelGenius user can have only one Google connection.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            unique = true
    )
    private User user;

    /**
     * Google account email address.
     */
    @Column(name = "google_email")
    private String googleEmail;

    /**
     * Current Google OAuth access token.
     */
    @Lob
    @Column(
            name = "access_token",
            columnDefinition = "TEXT"
    )
    private String accessToken;

    /**
     * Google OAuth refresh token.
     *
     * This is used to obtain a new access token
     * when the current access token expires.
     */
    @Lob
    @Column(
            name = "refresh_token",
            columnDefinition = "TEXT"
    )
    private String refreshToken;

    /**
     * Time when the current access token expires.
     */
    @Column(name = "token_expiry")
    private LocalDateTime tokenExpiry;

    /**
     * Timestamp when the Google connection was created.
     */
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    /**
     * Timestamp when the Google connection was last updated.
     */
    @Column(
            name = "updated_at",
            nullable = false
    )
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