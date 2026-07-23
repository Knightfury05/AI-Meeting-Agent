package com.meetingai.controller;

import com.meetingai.service.GoogleOAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/google")
@RequiredArgsConstructor
@Tag(name = "Google OAuth", description = "Google account connection and OAuth flow")
public class GoogleOAuthController {

    private final GoogleOAuthService googleOAuthService;


    /**
     * Starts the Google OAuth authorization process.
     *
     * Frontend calls:
     *
     * GET /api/google/oauth2/authorize
     *
     * The response contains the Google authorization URL.
     */
    @GetMapping("/oauth2/authorize")
    @Operation(summary = "Get Google OAuth URL", description = "Returns the Google authorization URL for the current user to connect their Google account.")
    @ApiResponse(responseCode = "200", description = "Authorization URL generated")
    public ResponseEntity<?> authorizeGoogle() {

        try {

            String authorizationUrl =
                    googleOAuthService
                            .getAuthorizationUrl();

            return ResponseEntity.ok(
                    Map.of(
                            "success", true,
                            "authorizationUrl",
                            authorizationUrl
                    )
            );

        } catch (Exception e) {

            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(
                            Map.of(
                                    "success", false,
                                    "message",
                                    "Failed to generate Google authorization URL",
                                    "error",
                                    e.getMessage()
                            )
                    );
        }
    }


    /**
     * Handles the OAuth callback from Google.
     *
     * Google redirects the user to:
     *
     * GET /api/google/oauth2/callback?code=...&state=...
     *
     * The state parameter contains the PixelGenius user ID
     * in the current local implementation.
     */
    @GetMapping("/oauth2/callback")
    @Operation(summary = "Google OAuth callback", description = "Handles the OAuth redirect from Google. Exchanges the authorization code for tokens and saves the connection.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Google account connected"),
        @ApiResponse(responseCode = "400", description = "Invalid OAuth state")
    })
    public ResponseEntity<?> googleCallback(
            @Parameter(description = "Authorization code from Google")
            @RequestParam("code") String code,
            @Parameter(description = "State parameter containing user ID")
            @RequestParam("state") String state
    ) {

        try {

            /*
             * Convert OAuth state to PixelGenius user ID.
             */
            Long userId =
                    Long.parseLong(state);


            /*
             * Exchange authorization code for tokens
             * and save the Google connection.
             */
            googleOAuthService
                    .handleCallback(
                            code,
                            userId
                    );


            return ResponseEntity.ok(
                    Map.of(
                            "success", true,
                            "message",
                            "Google account connected successfully"
                    )
            );

        } catch (NumberFormatException e) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "success", false,
                                    "message",
                                    "Invalid OAuth state"
                            )
                    );

        } catch (Exception e) {

            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(
                            Map.of(
                                    "success", false,
                                    "message",
                                    "Failed to connect Google account",
                                    "error",
                                    e.getMessage()
                            )
                    );
        }
    }


    /**
     * Checks whether the currently authenticated
     * PixelGenius user has connected Google.
     *
     * Frontend calls:
     *
     * GET /api/google/status
     */
    @GetMapping("/status")
    @Operation(summary = "Check Google connection status", description = "Returns whether the current user has a connected Google account.")
    @ApiResponse(responseCode = "200", description = "Connection status returned")
    public ResponseEntity<?> getGoogleStatus() {

        try {

            boolean connected =
                    googleOAuthService
                            .isGoogleConnected();

            return ResponseEntity.ok(
                    Map.of(
                            "success", true,
                            "connected", connected
                    )
            );

        } catch (Exception e) {

            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(
                            Map.of(
                                    "success", false,
                                    "message",
                                    "Failed to check Google connection status",
                                    "error",
                                    e.getMessage()
                            )
                    );
        }
    }


    /**
     * Disconnects the Google account from
     * the currently authenticated PixelGenius user.
     *
     * Frontend calls:
     *
     * DELETE /api/google/disconnect
     */
    @DeleteMapping("/disconnect")
    @Operation(summary = "Disconnect Google account", description = "Disconnects the current user's Google account from the application.")
    @ApiResponse(responseCode = "200", description = "Google account disconnected")
    public ResponseEntity<?> disconnectGoogle() {

        try {

            googleOAuthService
                    .disconnectGoogle();

            return ResponseEntity.ok(
                    Map.of(
                            "success", true,
                            "message",
                            "Google account disconnected successfully"
                    )
            );

        } catch (Exception e) {

            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(
                            Map.of(
                                    "success", false,
                                    "message",
                                    "Failed to disconnect Google account",
                                    "error",
                                    e.getMessage()
                            )
                    );
        }
    }
}