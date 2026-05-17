package com.codebaseqa.controller;

import com.codebaseqa.model.User;
import com.codebaseqa.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

    /**
     * Redirect to GitHub OAuth authorization page
     */
    @GetMapping("/github")
    public RedirectView redirectToGitHub() {
        // Include user:email scope to access private email addresses
        String scope = "repo,read:user,user:email";
        String authUrl = String.format(
            "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=%s",
            githubClientId,
            URLEncoder.encode(redirectUri, StandardCharsets.UTF_8),
            scope
        );
        
        log.info("Redirecting to GitHub OAuth: {}", authUrl);
        return new RedirectView(authUrl);
    }

    /**
     * Handle GitHub OAuth callback
     */
    @GetMapping("/github/callback")
    public ResponseEntity<?> handleGitHubCallback(@RequestParam String code) {
        try {
            log.info("Received GitHub OAuth callback with code");
            AuthService.AuthResponse response = authService.authenticateWithGithub(code);
            
            return ResponseEntity.ok(Map.of(
                "token", response.token(),
                "user", Map.of(
                    "id", response.user().getId(),
                    "username", response.user().getUsername(),
                    "email", response.user().getEmail() != null ? response.user().getEmail() : "",
                    "avatarUrl", response.user().getAvatarUrl() != null ? response.user().getAvatarUrl() : ""
                )
            ));
        } catch (Exception e) {
            log.error("GitHub OAuth failed", e);
            return ResponseEntity.status(401).body(Map.of(
                "error", "OAUTH_FAILED",
                "message", "Failed to authenticate with GitHub: " + e.getMessage()
            ));
        }
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
        
        public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
            return java.util.Map.of(k1, v1, k2, v2, k3, v3, k4, v4);
        }
        
        public static <K, V> java.util.Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
            return java.util.Map.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
        }
    }
}
