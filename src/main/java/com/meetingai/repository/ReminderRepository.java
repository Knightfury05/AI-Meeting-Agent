package com.meetingai.repository;

import com.meetingai.entity.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    /**
     * Find the reminder audit record for a given meeting, if the
     * reminder pipeline has already run for it.
     */
    Optional<Reminder> findByMeetingId(Long meetingId);

    /**
     * Whether a reminder has already been generated for this meeting —
     * used to keep the reminder step idempotent if the pipeline is
     * ever re-triggered for the same meeting.
     */
    boolean existsByMeetingId(Long meetingId);
}
