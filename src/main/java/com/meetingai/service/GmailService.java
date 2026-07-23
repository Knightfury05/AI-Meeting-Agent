package com.meetingai.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

@Service
@RequiredArgsConstructor
public class GmailService {

    private final GoogleOAuthService googleOAuthService;


    /**
     * Creates a Gmail API client from an
     * already-resolved credential.
     */
    private Gmail getGmailClient(
            Credential credential
    ) throws Exception {

        return new Gmail.Builder(
                com.google.api.client.googleapis.javanet
                        .GoogleNetHttpTransport
                        .newTrustedTransport(),

                com.google.api.client.json.gson
                        .GsonFactory
                        .getDefaultInstance(),

                credential
        )
                .setApplicationName(
                        "MeetingAI"
                )
                .build();
    }


    /**
     * Creates a Gmail API client using the
     * currently authenticated user's Google credentials.
     *
     * Only usable from a request thread with a populated
     * SecurityContext (i.e. from a controller). Background jobs
     * must use getGmailClient(Long userId) instead.
     */
    private Gmail getGmailClient()
            throws Exception {

        return getGmailClient(
                googleOAuthService
                        .getValidCredential()
        );
    }


    /**
     * Creates a Gmail API client for a specific user (by ID),
     * independent of the current request's SecurityContext.
     *
     * Used by background jobs — e.g. the async meeting pipeline —
     * that already know which user they're acting on behalf of but
     * don't run on that user's original request thread.
     */
    private Gmail getGmailClient(
            Long userId
    ) throws Exception {

        return getGmailClient(
                googleOAuthService
                        .getValidCredential(userId)
        );
    }


    /**
     * Sends an email using the authenticated
     * user's Gmail account.
     *
     * @param to Recipient email address
     * @param subject Email subject
     * @param body Email body
     *
     * @return Gmail Message containing the sent email ID
     */
    public Message sendEmail(
            String to,
            String subject,
            String body
    ) throws Exception {

        validateEmailFields(to, subject);

        return sendVia(
                getGmailClient(),
                to,
                subject,
                body
        );
    }


    /**
     * Sends an email on behalf of a specific user (by ID), independent
     * of the current request's SecurityContext.
     *
     * Used by background jobs (e.g. the meeting reminder pipeline)
     * that already know which user's Gmail account to send from.
     *
     * @param userId ID of the user whose Gmail account sends the email
     * @param to Recipient email address
     * @param subject Email subject
     * @param body Email body
     *
     * @return Gmail Message containing the sent email ID
     */
    public Message sendEmailForUser(
            Long userId,
            String to,
            String subject,
            String body
    ) throws Exception {

        validateEmailFields(to, subject);

        return sendVia(
                getGmailClient(userId),
                to,
                subject,
                body
        );
    }


    private void validateEmailFields(
            String to,
            String subject
    ) {

        /*
         * Validate recipient.
         */
        if (to == null
                || to.isBlank()) {

            throw new IllegalArgumentException(
                    "Recipient email address is required"
            );
        }


        /*
         * Validate subject.
         */
        if (subject == null
                || subject.isBlank()) {

            throw new IllegalArgumentException(
                    "Email subject is required"
            );
        }
    }


    /**
     * Shared send logic once a Gmail client has already been
     * resolved for whichever user is sending.
     */
    private Message sendVia(
            Gmail gmail,
            String to,
            String subject,
            String body
    ) throws Exception {

        /*
         * Create MIME email.
         */
        MimeMessage email =
                createMimeMessage(
                        to,
                        subject,
                        body
                );


        /*
         * Convert MIME email to a Gmail Message.
         */
        Message message =
                createGmailMessage(
                        email
                );


        /*
         * Send the email through Gmail API.
         *
         * "me" means the currently authenticated
         * Google/Gmail user.
         */
        return gmail
                .users()
                .messages()
                .send(
                        "me",
                        message
                )
                .execute();
    }


    /**
     * Creates a MIME email message.
     */
    private MimeMessage createMimeMessage(
            String to,
            String subject,
            String body
    ) throws Exception {

        /*
         * Create mail session.
         */
        Properties properties =
                new Properties();

        Session session =
                Session.getInstance(
                        properties
                );


        /*
         * Create MIME message.
         */
        MimeMessage email =
                new MimeMessage(
                        session
                );


        /*
         * Set recipient.
         */
        email.setRecipient(
                jakarta.mail.Message.RecipientType.TO,
                new InternetAddress(
                        to
                )
        );


        /*
         * Set subject.
         */
        email.setSubject(
                subject,
                StandardCharsets.UTF_8.name()
        );


        /*
         * Set email body.
         */
        email.setText(
                body,
                StandardCharsets.UTF_8.name()
        );


        return email;
    }


    /**
     * Converts the MIME email into the format
     * required by the Gmail API.
     */
    private Message createGmailMessage(
            MimeMessage email
    ) throws Exception {

        /*
         * Write MIME message to byte array.
         */
        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream();

        email.writeTo(
                buffer
        );


        /*
         * Encode email using URL-safe Base64.
         */
        String encodedEmail =
                Base64
                        .getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                buffer.toByteArray()
                        );


        /*
         * Create Gmail Message.
         */
        Message message =
                new Message();

        message.setRaw(
                encodedEmail
        );


        return message;
    }
}