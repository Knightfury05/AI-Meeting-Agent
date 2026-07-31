package com.meetingai.controller;

import com.meetingai.dto.AdminMeetingView;
import com.meetingai.dto.AdminUserView;
import com.meetingai.dto.DashboardStatsResponse;
import com.meetingai.dto.MeetingResponse;
import com.meetingai.dto.SystemStatsResponse;
import com.meetingai.dto.UserStatusRequest;
import com.meetingai.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Admin-only endpoints for managing users, meetings and system stats")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    @Operation(summary = "List all users", description = "Returns all registered users with their meeting counts. Admin only.")
    public ResponseEntity<List<AdminUserView>> getAllUsers() {
        return ResponseEntity.ok(adminService.getAllUsers());
    }

    @GetMapping("/meetings")
    @Operation(summary = "List all meetings", description = "Returns all meetings across all users. Admin only.")
    public ResponseEntity<List<AdminMeetingView>> getAllMeetings() {
        return ResponseEntity.ok(adminService.getAllMeetings());
    }

    @GetMapping("/stats")
    @Operation(summary = "System statistics", description = "Returns counts of users, meetings grouped by status. Admin only.")
    public ResponseEntity<SystemStatsResponse> getSystemStats() {
        return ResponseEntity.ok(adminService.getSystemStats());
    }

    @GetMapping("/meetings/{id}")
    @Operation(summary = "Get meeting detail", description = "Returns the full result of any meeting by id, regardless of owner. Admin only.")
    public ResponseEntity<MeetingResponse> getMeetingDetail(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.getMeetingDetail(id));
    }

    @PutMapping("/users/{id}/status")
    @Operation(summary = "Update user status", description = "Ban or unban a user (ACTIVE/INACTIVE). Cannot change your own status or ban the last admin. Admin only.")
    public ResponseEntity<AdminUserView> updateUserStatus(@PathVariable Long id, @RequestBody UserStatusRequest request) {
        return ResponseEntity.ok(adminService.updateUserStatus(id, request.getStatus()));
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Dashboard statistics", description = "Returns user/meeting counts and status breakdown for the admin dashboard. Admin only.")
    public ResponseEntity<DashboardStatsResponse> getDashboardStats() {
        return ResponseEntity.ok(adminService.getDashboardStats());
    }

    @GetMapping("/server/health")
    @Operation(summary = "Server health", description = "Returns CPU, memory, disk usage, uptime and JVM/OS details. Admin only.")
    public ResponseEntity<Map<String, Object>> getServerHealth() {
        return ResponseEntity.ok(adminService.getServerHealth());
    }

    @GetMapping("/analytics/active-users")
    @Operation(summary = "Active users analytics", description = "Returns daily active user counts for the given period (week or month). Admin only.")
    public ResponseEntity<Map<String, Object>> getActiveUsers(
            @RequestParam(defaultValue = "week") String period) {
        return ResponseEntity.ok(adminService.getActiveUsers(period));
    }
}
