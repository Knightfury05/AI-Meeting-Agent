package com.meetingai.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class GoogleIntegrationConfig {

    @Value("${google.calendar.client-id}")
    private String clientId;

    @Value("${google.calendar.client-secret}")
    private String clientSecret;

    /**
     * Google OAuth scopes required by PixelGenius, externalized to
     * application.yml (google.oauth.scopes) as a comma-separated list.
     *
     * Calendar:
     * Allows the application to create, update and delete
     * calendar events.
     *
     * Gmail:
     * Allows the application to send reminder emails
     * using the authenticated user's Gmail account.
     */
    @Value("${google.oauth.scopes}")
    private String scopesProperty;


    /**
     * Creates the Google OAuth authorization flow.
     *
     * The same OAuth flow is used for both:
     *
     * - Google Calendar
     * - Gmail
     */
    @Bean
    public GoogleAuthorizationCodeFlow googleAuthorizationCodeFlow()
            throws Exception {

        /*
         * Build Google client secrets from the values
         * stored in application.yml.
         */
        GoogleClientSecrets clientSecrets =
                new GoogleClientSecrets()
                        .setInstalled(
                                new GoogleClientSecrets.Details()
                                        .setClientId(clientId)
                                        .setClientSecret(clientSecret)
                        );


        /*
         * Parse the comma-separated scopes from application.yml.
         */
        List<String> scopes =
                Arrays.stream(scopesProperty.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());


        /*
         * Create the Google OAuth authorization flow.
         *
         * AccessType.OFFLINE is important because we need
         * a refresh token to continue accessing Calendar and
         * Gmail after the access token expires.
         */
        return new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport
                        .newTrustedTransport(),

                GsonFactory
                        .getDefaultInstance(),

                clientSecrets,

                scopes
        )
                .setAccessType("offline")
                .setApprovalPrompt("force")
                .build();
    }
}