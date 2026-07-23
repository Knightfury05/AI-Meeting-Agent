package com.meetingai.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.meetingai.entity.GoogleConnection;
import com.meetingai.entity.User;
import com.meetingai.repository.GoogleConnectionRepository;
import com.meetingai.repository.UserRepository;
import com.meetingai.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class GoogleOAuthService {

    private final GoogleAuthorizationCodeFlow googleAuthorizationCodeFlow;

    private final GoogleConnectionRepository googleConnectionRepository;

    private final UserRepository userRepository;

    private final CurrentUserProvider currentUserProvider;

    @Value("${google.calendar.client-id}")
    private String googleClientId;

    @Value("${google.calendar.client-secret}")
    private String googleClientSecret;

    @Value("${google.calendar.redirect-uri}")
    private String redirectUri;


    /**
     * Generates the Google OAuth authorization URL.
     */
    public String getAuthorizationUrl() {

        User currentUser =
                currentUserProvider.getCurrentUser();

        /*
         * For the current local implementation,
         * the PixelGenius user ID is passed as the OAuth state.
         */
        String state =
                currentUser.getId().toString();

        return googleAuthorizationCodeFlow
                .newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setState(state)
                .toString();
    }


    /**
     * Handles Google's OAuth callback.
     *
     * Exchanges the authorization code for:
     *
     * - Access Token
     * - Refresh Token
     * - Expiration Time
     */
    @Transactional
    public void handleCallback(
            String code,
            Long userId
    ) throws Exception {

        /*
         * Exchange the authorization code for
         * Google access and refresh tokens.
         */
        GoogleTokenResponse tokenResponse =
                googleAuthorizationCodeFlow
                        .newTokenRequest(code)
                        .setRedirectUri(redirectUri)
                        .execute();


        /*
         * Find an existing Google connection.
         *
         * If the user has never connected Google,
         * create a new connection.
         */
        GoogleConnection connection =
                googleConnectionRepository
                        .findByUserId(userId)
                        .orElseGet(() -> {

                            User user =
                                    userRepository
                                            .findById(userId)
                                            .orElseThrow(
                                                    () -> new IllegalStateException(
                                                            "User not found with ID: "
                                                                    + userId
                                                    )
                                            );

                            return GoogleConnection
                                    .builder()
                                    .user(user)
                                    .build();
                        });


        /*
         * Store the new access token.
         */
        connection.setAccessToken(
                tokenResponse.getAccessToken()
        );


        /*
         * Google may not return a refresh token
         * every time.
         *
         * Therefore, only replace the existing refresh
         * token when Google provides a new one.
         */
        if (tokenResponse.getRefreshToken() != null
                && !tokenResponse
                .getRefreshToken()
                .isBlank()) {

            connection.setRefreshToken(
                    tokenResponse.getRefreshToken()
            );
        }


        /*
         * Store the access token expiration time.
         */
        if (tokenResponse.getExpiresInSeconds() != null) {

            LocalDateTime expiry =
                    LocalDateTime
                            .now(ZoneOffset.UTC)
                            .plusSeconds(
                                    tokenResponse
                                            .getExpiresInSeconds()
                            );

            connection.setTokenExpiry(
                    expiry
            );
        }


        /*
         * Save the Google connection.
         */
        googleConnectionRepository.save(
                connection
        );
    }


    /**
     * Returns the Google connection for
     * the currently authenticated user.
     */
    @Transactional(readOnly = true)
    public GoogleConnection getCurrentUserConnection() {

        User currentUser =
                currentUserProvider.getCurrentUser();

        return googleConnectionRepository
                .findByUserId(
                        currentUser.getId()
                )
                .orElseThrow(
                        () -> new IllegalStateException(
                                "Google account is not connected. "
                                        + "Please connect your Google account first."
                        )
                );
    }


    /**
     * Checks whether the current user has
     * connected their Google account.
     */
    @Transactional(readOnly = true)
    public boolean isGoogleConnected() {

        User currentUser =
                currentUserProvider.getCurrentUser();

        return googleConnectionRepository
                .existsByUserId(
                        currentUser.getId()
                );
    }


    /**
     * Disconnects Google from the current user.
     */
    @Transactional
    public void disconnectGoogle() {

        User currentUser =
                currentUserProvider.getCurrentUser();

        googleConnectionRepository
                .deleteByUserId(
                        currentUser.getId()
                );
    }


    /**
     * Returns the Google connection for a specific user by ID.
     *
     * Unlike getCurrentUserConnection(), this does NOT go through
     * CurrentUserProvider/SecurityContextHolder — it's used by background
     * jobs (e.g. the async meeting pipeline) that run on their own thread
     * with no security context, but already know which user they're
     * working on behalf of.
     */
    @Transactional(readOnly = true)
    public GoogleConnection getConnectionForUser(Long userId) {

        return googleConnectionRepository
                .findByUserId(userId)
                .orElseThrow(
                        () -> new IllegalStateException(
                                "Google account is not connected for user id "
                                        + userId
                                        + ". Please connect Google first."
                        )
                );
    }


    /**
     * Checks whether a specific user (by ID) has connected Google.
     * Background-thread-safe counterpart to isGoogleConnected().
     */
    @Transactional(readOnly = true)
    public boolean isGoogleConnectedForUser(Long userId) {

        return googleConnectionRepository
                .existsByUserId(userId);
    }


    /**
     * Returns a valid Google OAuth Credential for a specific user (by ID).
     *
     * Background-thread-safe counterpart to getValidCredential() — used
     * by the async meeting pipeline, which cannot rely on
     * CurrentUserProvider since it runs outside the original HTTP
     * request's security context.
     */
    @Transactional
    public Credential getValidCredential(Long userId)
            throws Exception {

        GoogleConnection connection =
                getConnectionForUser(userId);

        return getValidCredential(
                connection
        );
    }


    /**
     * Returns a valid Google OAuth Credential.
     *
     * If the access token has expired or is about to expire,
     * it is refreshed automatically.
     */
    @Transactional
    public Credential getValidCredential()
            throws Exception {

        GoogleConnection connection =
                getCurrentUserConnection();

        return getValidCredential(
                connection
        );
    }


    /**
     * Creates a Google Credential using the
     * stored access and refresh tokens.
     */
    @Transactional
    public Credential getValidCredential(
            GoogleConnection connection
    ) throws Exception {

        /*
         * Create Credential using the Google API
         * client's configured transport and JSON factory.
         */
        Credential credential =
                new Credential.Builder(
                        com.google.api.client.auth.oauth2
                                .BearerToken
                                .authorizationHeaderAccessMethod()
                )
                        .setTransport(
                                googleAuthorizationCodeFlow
                                        .getTransport()
                        )
                        .setJsonFactory(
                                googleAuthorizationCodeFlow
                                        .getJsonFactory()
                        )
                        // Credential.setRefreshToken() (called below) requires
                        // these two to be set — without them it throws
                        // "Please use the Builder and call setJsonFactory,
                        // setTransport, setClientAuthentication and
                        // setTokenServerUrl/setTokenServerEncodedUrl", since a
                        // refresh token is useless without knowing how/where
                        // to redeem it for a new access token.
                        .setClientAuthentication(
                                googleAuthorizationCodeFlow
                                        .getClientAuthentication()
                        )
                        .setTokenServerEncodedUrl(
                                googleAuthorizationCodeFlow
                                        .getTokenServerEncodedUrl()
                        )
                        .build();


        /*
         * Set stored access token.
         */
        credential.setAccessToken(
                connection.getAccessToken()
        );


        /*
         * Set stored refresh token.
         */
        credential.setRefreshToken(
                connection.getRefreshToken()
        );


        /*
         * Set token expiration time.
         */
        if (connection.getTokenExpiry() != null) {

            credential.setExpirationTimeMilliseconds(
                    connection
                            .getTokenExpiry()
                            .toInstant(
                                    ZoneOffset.UTC
                            )
                            .toEpochMilli()
            );
        }


        /*
         * Determine whether the token needs refreshing.
         *
         * Refresh when:
         *
         * - Expiration is unknown
         * - Token has expired
         * - Token expires within 60 seconds
         */
        boolean tokenNeedsRefresh =
                connection.getTokenExpiry() == null
                        ||
                        connection
                                .getTokenExpiry()
                                .isBefore(
                                        LocalDateTime
                                                .now(ZoneOffset.UTC)
                                                .plusSeconds(60)
                                );


        /*
         * Refresh if necessary.
         */
        if (tokenNeedsRefresh) {

            refreshAccessToken(
                    connection,
                    credential
            );
        }


        return credential;
    }


    /**
     * Refreshes the Google access token.
     *
     * Uses GoogleRefreshTokenRequest directly because
     * Credential.executeRefreshToken() is protected.
     */
    private void refreshAccessToken(
            GoogleConnection connection,
            Credential credential
    ) throws Exception {

        /*
         * Make sure a refresh token exists.
         */
        if (connection.getRefreshToken() == null
                || connection
                .getRefreshToken()
                .isBlank()) {

            throw new IllegalStateException(
                    "Google refresh token is missing. "
                            + "Please reconnect your Google account."
            );
        }


        /*
         * Create a Google refresh token request.
         *
         * Client ID and client secret are explicitly
         * loaded from application.yml.
         */
        GoogleRefreshTokenRequest refreshRequest =
                new GoogleRefreshTokenRequest(
                        googleAuthorizationCodeFlow
                                .getTransport(),

                        googleAuthorizationCodeFlow
                                .getJsonFactory(),

                        connection
                                .getRefreshToken(),

                        googleClientId,

                        googleClientSecret
                );


        /*
         * Execute refresh request.
         */
        TokenResponse tokenResponse =
                refreshRequest.execute();


        /*
         * Store new access token.
         */
        connection.setAccessToken(
                tokenResponse
                        .getAccessToken()
        );


        /*
         * Google usually does not return a new refresh token.
         *
         * If it does, update the stored value.
         */
        if (tokenResponse.getRefreshToken() != null
                && !tokenResponse
                .getRefreshToken()
                .isBlank()) {

            connection.setRefreshToken(
                    tokenResponse
                            .getRefreshToken()
            );
        }


        /*
         * Calculate new expiration time.
         */
        if (tokenResponse.getExpiresInSeconds() != null) {

            LocalDateTime newExpiry =
                    LocalDateTime
                            .now(ZoneOffset.UTC)
                            .plusSeconds(
                                    tokenResponse
                                            .getExpiresInSeconds()
                            );

            connection.setTokenExpiry(
                    newExpiry
            );
        }


        /*
         * Save updated tokens to MySQL.
         */
        googleConnectionRepository.save(
                connection
        );


        /*
         * Update Credential object.
         */
        credential.setAccessToken(
                connection.getAccessToken()
        );

        credential.setRefreshToken(
                connection.getRefreshToken()
        );


        /*
         * Update Credential expiration time.
         */
        if (connection.getTokenExpiry() != null) {

            credential
                    .setExpirationTimeMilliseconds(
                            connection
                                    .getTokenExpiry()
                                    .toInstant(
                                            ZoneOffset.UTC
                                    )
                                    .toEpochMilli()
                    );
        }
    }
}