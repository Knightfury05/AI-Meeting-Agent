package com.meetingai.service;

import com.meetingai.dto.AdminMeetingView;
import com.meetingai.dto.AdminUserView;
import com.meetingai.dto.DashboardStatsResponse;
import com.meetingai.dto.MeetingResponse;
import com.meetingai.dto.SystemStatsResponse;
import com.meetingai.entity.MeetingStatus;
import com.meetingai.entity.Role;
import com.meetingai.entity.User;
import com.meetingai.entity.UserStatus;
import com.meetingai.repository.MeetingRepository;
import com.meetingai.repository.UserRepository;
import com.meetingai.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final UserRepository userRepository;
    private final MeetingRepository meetingRepository;
    private final MeetingService meetingService;
    private final CurrentUserProvider currentUserProvider;

    public AdminService(UserRepository userRepository,
                        MeetingRepository meetingRepository,
                        MeetingService meetingService,
                        CurrentUserProvider currentUserProvider) {
        this.userRepository = userRepository;
        this.meetingRepository = meetingRepository;
        this.meetingService = meetingService;
        this.currentUserProvider = currentUserProvider;
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
        return meetingRepository.findAllWithUserOrderByCreatedAtDesc().stream()
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
                .pendingCount(pending)
                .completedCount(completed)
                .failedCount(failed)
                .processingCount(transcribing + summarizing)
                .build();
    }

    @Transactional(readOnly = true)
    public MeetingResponse getMeetingDetail(Long id) {
        return meetingService.getByIdForAdmin(id);
    }

    @Transactional
    public AdminUserView updateUserStatus(Long userId, String status) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new java.util.NoSuchElementException("User not found: " + userId));

        Long currentUserId = currentUserProvider.getCurrentUserId();
        if (user.getId().equals(currentUserId)) {
            throw new IllegalArgumentException("Cannot change your own status");
        }

        if (user.getRole() == Role.ADMIN && status.equals("INACTIVE")) {
            long adminCount = userRepository.countByRole(Role.ADMIN);
            if (adminCount <= 1) {
                throw new IllegalArgumentException("Cannot ban the last admin");
            }
        }

        user.setStatus(UserStatus.valueOf(status));
        user = userRepository.save(user);
        log.info("User id={} status changed to {}", user.getId(), user.getStatus());
        return AdminUserView.from(user, meetingRepository.countByUserId(user.getId()));
    }

    @Transactional(readOnly = true)
    public DashboardStatsResponse getDashboardStats() {
        long totalUsers = userRepository.count();
        long adminUsers = userRepository.countByRole(Role.ADMIN);
        long regularUsers = totalUsers - adminUsers;
        long totalMeetings = meetingRepository.count();

        Map<String, Long> breakdown = new HashMap<>();
        long inProgress = 0;
        for (MeetingStatus status : MeetingStatus.values()) {
            long count = meetingRepository.countByStatus(status);
            breakdown.put(status.name(), count);
            if (status == MeetingStatus.TRANSCRIBING || status == MeetingStatus.SUMMARIZING) {
                inProgress += count;
            }
        }

        return DashboardStatsResponse.builder()
                .totalUsers(totalUsers)
                .adminUsers(adminUsers)
                .regularUsers(regularUsers)
                .totalMeetings(totalMeetings)
                .inProgressMeetings(inProgress)
                .statusBreakdown(breakdown)
                .build();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getServerHealth() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        Runtime runtime = Runtime.getRuntime();
        File root = new File(".");

        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("systemLoadAverage", osBean.getSystemLoadAverage());
        cpu.put("availableProcessors", osBean.getAvailableProcessors());

        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedBytes", usedMemory);
        memory.put("maxBytes", maxMemory);
        memory.put("usedFormatted", formatBytes(usedMemory));
        memory.put("maxFormatted", formatBytes(maxMemory));
        memory.put("usagePercent", maxMemory > 0 ? Math.round((double) usedMemory / maxMemory * 100) : 0);

        Map<String, Object> disk = new LinkedHashMap<>();
        disk.put("totalBytes", root.getTotalSpace());
        disk.put("freeBytes", root.getFreeSpace());
        disk.put("usableBytes", root.getUsableSpace());
        disk.put("totalFormatted", formatBytes(root.getTotalSpace()));
        disk.put("freeFormatted", formatBytes(root.getFreeSpace()));
        disk.put("usagePercent", root.getTotalSpace() > 0
                ? Math.round((double) (root.getTotalSpace() - root.getFreeSpace()) / root.getTotalSpace() * 100)
                : 0);

        long uptimeMs = runtimeBean.getUptime();
        long uptimeSeconds = uptimeMs / 1000;
        long days = uptimeSeconds / 86400;
        long hours = (uptimeSeconds % 86400) / 3600;
        long minutes = (uptimeSeconds % 3600) / 60;

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("cpu", cpu);
        health.put("memory", memory);
        health.put("disk", disk);
        health.put("uptimeMs", uptimeMs);
        health.put("uptimeFormatted", String.format("%dd %dh %dm", days, hours, minutes));
        health.put("jvmName", runtimeBean.getVmName());
        health.put("jvmVersion", runtimeBean.getVmVersion());
        health.put("osName", osBean.getName());
        health.put("osVersion", osBean.getVersion());

        return health;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getActiveUsers(String period) {
        LocalDateTime since;
        switch (period.toLowerCase()) {
            case "month":
                since = LocalDateTime.now().minusMonths(1);
                break;
            case "week":
            default:
                since = LocalDateTime.now().minusWeeks(1);
                break;
        }

        List<Object[]> raw = meetingRepository.countDailyActiveUsersSince(since);
        long totalActive = meetingRepository.countActiveUsersSince(since);

        List<Map<String, Object>> dailyCounts = new ArrayList<>();
        for (Object[] row : raw) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", row[0] != null ? row[0].toString() : null);
            entry.put("activeUsers", row[1] != null ? ((Number) row[1]).longValue() : 0);
            dailyCounts.add(entry);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("period", period);
        response.put("since", since.format(DateTimeFormatter.ISO_LOCAL_DATE));
        response.put("totalActiveUsers", totalActive);
        response.put("dailyCounts", dailyCounts);

        return response;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }
}
