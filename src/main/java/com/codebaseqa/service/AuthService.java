package com.codebaseqa.service;

import com.codebaseqa.model.User;
import com.codebaseqa.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final WebClient.Builder webClientBuilder;

    @Value("${app.github.client-id}")
    private String clientId;

    @Value("${app.github.client-secret}")
    private String clientSecret;

    /**
     * Exchange GitHub OAuth code for access token, fetch user profile,
     * upsert user in DB, and return a JWT.
     */
    public AuthResponse authenticateWithGithub(String code) {
        // 1. Exchange code for GitHub access token
        String githubToken = exchangeCodeForToken(code);

        // 2. Fetch user profile from GitHub
        GitHubUser ghUser = fetchGitHubUser(githubToken);

        // 3. Upsert user in database
        User user = userRepository.findByGithubId(ghUser.id())
            .map(existing -> {
                existing.setUsername(ghUser.login());
                existing.setEmail(ghUser.email());
                existing.setAvatarUrl(ghUser.avatarUrl());
                existing.setGithubToken(githubToken);
                return userRepository.save(existing);
            })
            .orElseGet(() -> userRepository.save(User.builder()
                .githubId(ghUser.id())
                .username(ghUser.login())
                .email(ghUser.email())
                .avatarUrl(ghUser.avatarUrl())
                .githubToken(githubToken)
                .build()));

        // 4. Generate JWT
        String jwt = jwtService.generateToken(user);

        log.info("User authenticated: {} ({})", user.getUsername(), user.getId());
        return new AuthResponse(jwt, user);
    }

    private String exchangeCodeForToken(String code) {
        log.info("=== Exchanging OAuth code for access token ===");
        
        Map<String, Object> response = webClientBuilder.build()
            .post()
            .uri("https://github.com/login/oauth/access_token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code
            ))
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(Map.class)
            .block();

        if (response == null || !response.containsKey("access_token")) {
            log.error("Failed to exchange code for token. Response: {}", response);
            throw new RuntimeException("Failed to exchange code for token");
        }

        String accessToken = (String) response.get("access_token");
        String scope = (String) response.get("scope");
        
        log.info("Successfully obtained GitHub access token");
        log.info("Token length: {}", accessToken != null ? accessToken.length() : 0);
        log.info("Token prefix: {}", accessToken != null && accessToken.length() > 10 ? accessToken.substring(0, 10) + "..." : "N/A");
        log.info("Granted scopes: {}", scope);
        
        return accessToken;
    }

    private GitHubUser fetchGitHubUser(String token) {
        Map<String, Object> response = webClientBuilder.build()
            .get()
            .uri("https://api.github.com/user")
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .bodyToMono(Map.class)
            .block();

        if (response == null) {
            throw new RuntimeException("Failed to fetch GitHub user");
        }

        // GitHub API returns avatar_url (with underscore), not avatarUrl
        // Store github_id as String to avoid overflow issues with large IDs
        String id = response.get("id").toString();
        String login = (String) response.get("login");
        String email = (String) response.get("email"); // May be null if not public
        String avatarUrl = (String) response.get("avatar_url"); // Note: underscore

        // If email is null, try to fetch from /user/emails endpoint
        if (email == null || email.isEmpty()) {
            email = fetchPrimaryEmail(token);
        }

        return new GitHubUser(id, login, email, avatarUrl);
    }

    private String fetchPrimaryEmail(String token) {
        try {
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> emails = webClientBuilder.build()
                .get()
                .uri("https://api.github.com/user/emails")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(java.util.List.class)
                .block();

            if (emails != null && !emails.isEmpty()) {
                // Find the primary email
                for (Map<String, Object> emailObj : emails) {
                    Boolean primary = (Boolean) emailObj.get("primary");
                    Boolean verified = (Boolean) emailObj.get("verified");
                    if (Boolean.TRUE.equals(primary) && Boolean.TRUE.equals(verified)) {
                        return (String) emailObj.get("email");
                    }
                }
                // If no primary email, return the first verified one
                for (Map<String, Object> emailObj : emails) {
                    Boolean verified = (Boolean) emailObj.get("verified");
                    if (Boolean.TRUE.equals(verified)) {
                        return (String) emailObj.get("email");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch user emails: {}", e.getMessage());
        }
        return null;
    }

    public User getCurrentUser(String userId) {
        return userRepository.findById(java.util.UUID.fromString(userId))
            .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public record GitHubUser(String id, String login, String email, String avatarUrl) {}
    public record AuthResponse(String token, User user) {}
}
