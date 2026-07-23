package com.meetingai.dto;

import com.meetingai.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class AdminUserView {
    private Long id;
    private String name;
    private String email;
    private String role;
    private String createdAt;
    private long meetingCount;

    public static AdminUserView from(User user, long meetingCount) {
        return AdminUserView.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)
                .meetingCount(meetingCount)
                .build();
    }
}
