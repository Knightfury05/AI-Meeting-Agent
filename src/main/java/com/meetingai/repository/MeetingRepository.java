package com.meetingai.repository;

import com.meetingai.entity.Meeting;
import com.meetingai.entity.MeetingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    long countByStatus(MeetingStatus status);
    long countByUserId(Long userId);

    // Admin: all meetings with user loaded, most recent first
    @Query("SELECT m FROM Meeting m JOIN FETCH m.user ORDER BY m.createdAt DESC")
    List<Meeting> findAllWithUserOrderByCreatedAtDesc();

    // Admin analytics: distinct active users who created meetings after a given date
    @Query("SELECT COUNT(DISTINCT m.user.id) FROM Meeting m WHERE m.createdAt >= :since")
    long countActiveUsersSince(LocalDateTime since);

    // Admin analytics: daily active user counts for the date range (MySQL native)
    @Query(value = "SELECT DATE(m.created_at), COUNT(DISTINCT m.user_id) FROM meetings m WHERE m.created_at >= ?1 GROUP BY DATE(m.created_at) ORDER BY DATE(m.created_at)", nativeQuery = true)
    List<Object[]> countDailyActiveUsersSince(LocalDateTime since);

    // --- Ownership-scoped queries: every list/lookup the controller exposes
    // goes through one of these instead of the unscoped methods above, so a
    // user can only ever see their own meetings. ---

    List<Meeting> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Meeting> findByIdAndUserId(Long id, Long userId);
}