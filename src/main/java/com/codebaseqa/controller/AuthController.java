package com.codebaseqa.controller;

import com.codebaseqa.model.User;
import com.codebaseqa.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @Value("${app.github.client-id}")
    private String githubClientId;

    @Value("${app.github.redirect-uri}")
    private String redirectUri;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    /**
     * List of allowed redirect URIs to prevent open redirect attacks
     */
    private static final List<String> ALLOWED_REDIRECT_ORIGINS = Arrays.asList(
        "http://localhost:5173",
        "http://localhost:3000"
    );

    /**
     * Redirect to GitHub OAuth authorization page
     */
    @GetMapping("/github")
    public void redirectToGitHub(
        @RequestParam(required = false) String redirect_uri,
        HttpServletResponse response
    ) throws IOException {
        // Validate redirect_uri if provided
        String validatedRedirectUri = validateAndSanitizeRedirectUri(redirect_uri);
        
        // Encode the redirect_uri in the state parameter to preserve it through OAuth flow
        String state = URLEncoder.encode(validatedRedirectUri, StandardCharsets.UTF_8);
        
        // Include user:email scope to access private email addresses
        String scope = "repo,read:user,user:email";
        String authUrl = String.format(
            "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=%s&state=%s",
            githubClientId,
            URLEncoder.encode(redirectUri, StandardCharsets.UTF_8),
            scope,
            state
        );
        
        log.info("Redirecting to GitHub OAuth with redirect_uri: {}", validatedRedirectUri);
        response.sendRedirect(authUrl);
    }

    /**
     * Handle GitHub OAuth callback
     */
    @GetMapping("/github/callback")
    public void handleGitHubCallback(
        @RequestParam String code,
        @RequestParam(required = false) String state,
        HttpServletResponse response
    ) throws IOException {
        try {
            log.info("=== GitHub OAuth Callback ===");
            log.info("Received code: {}", code.substring(0, Math.min(10, code.length())) + "...");
            log.info("Received state: {}", state);
            
            // Extract redirect_uri from state parameter
            String redirectUri = null;
            if (state != null && !state.isEmpty()) {
                try {
                    redirectUri = java.net.URLDecoder.decode(state, StandardCharsets.UTF_8);
                    log.info("Decoded redirect_uri from state: {}", redirectUri);
                } catch (Exception e) {
                    log.warn("Failed to decode state parameter: {}", state, e);
                }
            }
            
            // Validate redirect URI
            if (!isValidRedirectUri(redirectUri)) {
                redirectUri = frontendUrl + "/auth/callback";
                log.info("Using default redirect URI: {}", redirectUri);
            } else {
                log.info("Using validated redirect URI: {}", redirectUri);
            }
            
            // Authenticate with GitHub
            log.info("Calling authService.authenticateWithGithub...");
            AuthService.AuthResponse authResponse = authService.authenticateWithGithub(code);
            log.info("Authentication successful for user: {}", authResponse.user().getUsername());
            
            // Redirect to frontend with token
            String redirectUrl = redirectUri + "?token=" + authResponse.token();
            log.info("Redirecting to frontend: {}", redirectUri + "?token=<redacted>");
            response.sendRedirect(redirectUrl);
            
        } catch (Exception e) {
            log.error("=== GitHub OAuth Failed ===", e);
            log.error("Error type: {}", e.getClass().getName());
            log.error("Error message: {}", e.getMessage());
            
            // Extract redirect_uri from state parameter for error redirect
            String redirectUri = null;
            if (state != null && !state.isEmpty()) {
                try {
                    redirectUri = java.net.URLDecoder.decode(state, StandardCharsets.UTF_8);
                } catch (Exception ex) {
                    log.warn("Failed to decode state parameter: {}", state);
                }
            }
            
            // Validate redirect URI for error redirect
            if (!isValidRedirectUri(redirectUri)) {
                redirectUri = frontendUrl + "/auth/callback";
            }
            
            // Redirect to frontend with error
            String errorUrl = redirectUri + "?error=auth_failed&message=" + 
                URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8);
            log.info("Redirecting to frontend with error: {}", redirectUri + "?error=auth_failed");
            response.sendRedirect(errorUrl);
        }
    }

    /**
     * Validate redirect URI to prevent open redirect attacks
     */
    private boolean isValidRedirectUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        
        return ALLOWED_REDIRECT_ORIGINS.stream()
            .anyMatch(allowedOrigin -> uri.startsWith(allowedOrigin));
    }

    /**
     * Validate and sanitize redirect URI
     */
    private String validateAndSanitizeRedirectUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return frontendUrl + "/auth/callback";
        }
        
        if (isValidRedirectUri(uri)) {
            return uri;
        }
        
        log.warn("Invalid redirect URI attempted: {}", uri);
        return frontendUrl + "/auth/callback";
    }

    /**
     * Get current authenticated user
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        try {
            // The principal is now a User object (not a String)
            User user = (User) authentication.getPrincipal();
            
            return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "email", user.getEmail() != null ? user.getEmail() : "",
                "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                "createdAt", user.getCreatedAt()
            ));
        } catch (Exception e) {
            log.error("Failed to get current user", e);
            return ResponseEntity.status(401).body(Map.of(
                "error", "UNAUTHORIZED",
                "message", "Failed to get user info"
            ));
        }
    }

    /**
     * Logout (client should delete the JWT)
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    // Helper to avoid importing java.util.Map
    private static class Map {
        public static <K, V> java.util.Map<K, V> of(K k1, V v1) {
            return java.util.Map.of(k1, v1);
        }
        
        public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2) {
            return java.util.Map.of(k1, v1, k2, v2);
        }
        
        public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
            return java.util.Map.of(k1, v1, k2, v2, k3, v3);
        }
        
        public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
            return java.util.Map.of(k1, v1, k2, v2, k3, v3, k4, v4);
        }
        
        public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
            return java.util.Map.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
        }
    }
}
