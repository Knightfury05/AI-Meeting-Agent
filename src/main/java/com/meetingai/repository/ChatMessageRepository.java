package com.meetingai.repository;

import com.meetingai.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // Oldest first — this is chat display order, and also the order the
    // prompt-building code replays history back to the model in.
    List<ChatMessage> findAllByMeetingIdOrderByCreatedAtAsc(Long meetingId);
}
