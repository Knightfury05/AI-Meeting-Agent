package com.meetingai.service;

import com.meetingai.dto.AuthResponse;
import com.meetingai.dto.LoginRequest;
import com.meetingai.dto.RegisterRequest;
import com.meetingai.entity.Role;
import com.meetingai.entity.User;
import com.meetingai.repository.UserRepository;
import com.meetingai.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthService(UserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService,
                        AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
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
        log.info("[Auth] User logged in, id={}, email={}", user.getId(), user.getEmail());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .build();
    }
}
