package com.meetingai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.reset-password-url}")
    private String resetPasswordUrl;

    @Value("${app.reset-token-expiry-minutes}")
    private int resetTokenExpiryMinutes;

    public MailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendPasswordResetEmail(String to, String token) {
        String resetLink = resetPasswordUrl + "?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("Reset your MeetingAI password");
        message.setText("Hello,\n\n"
                + "We received a request to reset your MeetingAI password. "
                + "Click the link below to choose a new password:\n\n"
                + resetLink + "\n\n"
                + "This link is valid for " + resetTokenExpiryMinutes + " minutes and can only be used once. "
                + "If you didn't request this, you can safely ignore this email.\n\n"
                + "— MeetingAI");

        mailSender.send(message);
        log.info("[Mail] Password reset email sent to {}", to);
    }
}
