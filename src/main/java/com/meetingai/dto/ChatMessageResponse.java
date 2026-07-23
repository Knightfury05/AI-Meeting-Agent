package com.meetingai.dto;

import com.meetingai.entity.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageResponse {
    private Long id;
    private String sender; // "USER" | "AI"
    private String content;
    private String createdAt;

    public static ChatMessageResponse from(ChatMessage m) {
        return ChatMessageResponse.builder()
                .id(m.getId())
                .sender(m.getSender().name())
                .content(m.getContent())
                .createdAt(m.getCreatedAt() != null ? m.getCreatedAt().toString() : null)
                .build();
    }
}
