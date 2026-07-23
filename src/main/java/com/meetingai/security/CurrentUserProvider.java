package com.meetingai.security;

import com.meetingai.entity.User;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Single place that reads "who is making this request" out of the
 * SecurityContext. Every service method that should be scoped to the
 * logged-in user goes through here instead of re-reading
 * SecurityContextHolder directly — keeps the "whose data is this" logic
 * in exactly one spot.
 */
@Component
public class CurrentUserProvider {

    public User getCurrentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof User user) {
            return user;
        }
        throw new IllegalStateException("No authenticated user found in security context");
    }

    public Long getCurrentUserId() {
        return getCurrentUser().getId();
    }
}
