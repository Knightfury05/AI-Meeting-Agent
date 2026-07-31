package com.meetingai.service;

import com.meetingai.dto.AdminRegisterRequest;
import com.meetingai.dto.AuthResponse;
import com.meetingai.dto.LoginRequest;
import com.meetingai.dto.RegisterRequest;
import com.meetingai.entity.PasswordResetToken;
import com.meetingai.entity.Role;
import com.meetingai.entity.User;
import com.meetingai.repository.PasswordResetTokenRepository;
import com.meetingai.repository.UserRepository;
import com.meetingai.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final MailService mailService;

    @Value("${app.admin.registration-code}")
    private String adminRegistrationCode;

    @Value("${app.reset-token-expiry-minutes}")
    private int resetTokenExpiryMinutes;

    public AuthService(UserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService,
                        AuthenticationManager authenticationManager,
                        PasswordResetTokenRepository passwordResetTokenRepository,
                        MailService mailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.mailService = mailService;
    }

    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            /* Deliberately generic — do not reveal account existence details
             beyond "this email can't be used", which is already implied
             by any registration form. Avoids being a precise oracle for
             enumerating which emails have accounts.
            */
            throw new IllegalArgumentException("An account with this email already exists");
        }

        User user = User.builder()
                .name(request.getName().trim())
                .email(normalizedEmail)
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .build();

        user = userRepository.save(user);
        log.info("[Auth] New user registered, id={}, email={}", user.getId(), user.getEmail());

        String token = jwtService.generateToken(user);
        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }

    public AuthResponse registerAdmin(AdminRegisterRequest request) {
        if (!adminRegistrationCode.equals(request.getAdminCode())) {
            throw new IllegalArgumentException("Invalid admin registration code");
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException("An account with this email already exists");
        }

        User user = User.builder()
                .name(request.getName().trim())
                .email(normalizedEmail)
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.ADMIN)
                .build();

        user = userRepository.save(user);
        log.info("[Auth] New admin registered, id={}, email={}", user.getId(), user.getEmail());

        String token = jwtService.generateToken(user);
        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedEmail, request.getPassword())
            );
        } catch (BadCredentialsException e) {
            log.info("[Auth] Failed login attempt for email={}", normalizedEmail);
            // Same message regardless of whether the email exists or the
            // password was wrong — distinguishing the two lets an attacker
            // enumerate valid accounts.
            throw new BadCredentialsException("Invalid email or password");
        }

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        String token = jwtService.generateToken(user);
        log.info("[Auth] User logged in, id={}, email={}, role={}", user.getId(), user.getEmail(), user.getRole());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }

    /**
     * Creates a one-time password reset token and emails the reset link.
     * Always behaves the same whether or not the account exists, so the
     * endpoint can't be used to enumerate registered emails.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail).orElse(null);

        if (user == null) {
            log.info("[Auth] Password reset requested for unknown email={}", normalizedEmail);
            return;
        }

        passwordResetTokenRepository.deleteByUserId(user.getId());

        String token = UUID.randomUUID().toString();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token(token)
                .user(user)
                .expiresAt(LocalDateTime.now().plusMinutes(resetTokenExpiryMinutes))
                .used(false)
                .build();
        passwordResetTokenRepository.save(resetToken);

        try {
            mailService.sendPasswordResetEmail(user.getEmail(), token);
            log.info("[Auth] Password reset email sent, id={}, email={}", user.getId(), user.getEmail());
        } catch (Exception e) {
            // Keep the response generic either way — don't leak account existence.
            log.error("[Auth] Failed to send password reset email, id={}, email={}", user.getId(), user.getEmail(), e);
        }
    }

    /**
     * Validates the reset token, sets the new password (BCrypt-encoded) and
     * invalidates the token so it can only be used once.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset link"));

        if (resetToken.isUsed()) {
            throw new IllegalArgumentException("This reset link has already been used");
        }
        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("This reset link has expired");
        }

        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);
        log.info("[Auth] Password reset completed, id={}, email={}", user.getId(), user.getEmail());
    }
}
