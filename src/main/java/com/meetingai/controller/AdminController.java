package com.meetingai.controller;

import com.meetingai.dto.AdminMeetingView;
import com.meetingai.dto.AdminUserView;
import com.meetingai.dto.SystemStatsResponse;
import com.meetingai.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}
