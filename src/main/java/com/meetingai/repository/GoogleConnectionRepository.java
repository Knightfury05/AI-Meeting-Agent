package com.meetingai.repository;

import com.meetingai.entity.GoogleConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GoogleConnectionRepository
        extends JpaRepository<GoogleConnection, Long> {

    /**
     * Find the Google connection belonging to a specific
     * PixelGenius user.
     */
    Optional<GoogleConnection> findByUserId(Long userId);

    /**
     * Check whether a PixelGenius user has connected
     * their Google account.
     */
    boolean existsByUserId(Long userId);

    /**
     * Delete the Google connection belonging to a user.
     * This can be used later for "Disconnect Google".
     */
    void deleteByUserId(Long userId);
}