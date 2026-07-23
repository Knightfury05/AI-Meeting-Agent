package com.meetingai.service;

import com.meetingai.dto.AdminMeetingView;
import com.meetingai.dto.AdminUserView;
import com.meetingai.dto.SystemStatsResponse;
import com.meetingai.entity.MeetingStatus;
import com.meetingai.entity.User;
import com.meetingai.repository.MeetingRepository;
import com.meetingai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final UserRepository userRepository;
    private final MeetingRepository meetingRepository;

    public AdminService(UserRepository userRepository, MeetingRepository meetingRepository) {
        this.userRepository = userRepository;
        this.meetingRepository = meetingRepository;
    }

    @Transactional(readOnly = true)
    public List<AdminUserView> getAllUsers() {
        List<User> users = userRepository.findAll();
        return users.stream()
                .map(user -> AdminUserView.from(user, meetingRepository.countByUserId(user.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminMeetingView> getAllMeetings() {
        return meetingRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(AdminMeetingView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public SystemStatsResponse getSystemStats() {
        long totalUsers = userRepository.count();
        long totalMeetings = meetingRepository.count();
        long pending = meetingRepository.countByStatus(MeetingStatus.PENDING);
        long transcribing = meetingRepository.countByStatus(MeetingStatus.TRANSCRIBING);
        long summarizing = meetingRepository.countByStatus(MeetingStatus.SUMMARIZING);
        long completed = meetingRepository.countByStatus(MeetingStatus.COMPLETED);
        long failed = meetingRepository.countByStatus(MeetingStatus.FAILED);

        return SystemStatsResponse.builder()
                .totalUsers(totalUsers)
                .totalMeetings(totalMeetings)
                .meetingsPending(pending)
                .meetingsCompleted(completed)
                .meetingsFailed(failed)
                .meetingsProcessing(transcribing + summarizing)
                .build();
    }
}
