package com.meetingai.repository;

import com.meetingai.entity.Meeting;
import com.meetingai.entity.MeetingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    // All meetings with a given status (e.g. find everything still PENDING)
    List<Meeting> findByStatus(MeetingStatus status);

    // Search by title (partial match, case-insensitive) — useful for a search bar later
    List<Meeting> findByTitleContainingIgnoreCase(String title);
    
    // Most recent meetings first
    List<Meeting> findAllByOrderByCreatedAtDesc();

    // --- Ownership-scoped queries: every list/lookup the controller exposes
    // goes through one of these instead of the unscoped methods above, so a
    // user can only ever see their own meetings. ---

    List<Meeting> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Meeting> findByIdAndUserId(Long id, Long userId);
}