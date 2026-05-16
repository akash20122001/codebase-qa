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
        String jwt = jwtService.generateToken(user.getId().toString());

        log.info("User authenticated: {} ({})", user.getUsername(), user.getId());
        return new AuthResponse(jwt, user);
    }

    private String exchangeCodeForToken(String code) {
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
            throw new RuntimeException("Failed to exchange code for token");
        }

        return (String) response.get("access_token");
    }

    private GitHubUser fetchGitHubUser(String token) {
        return webClientBuilder.build()
            .get()
            .uri("https://api.github.com/user")
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .bodyToMono(GitHubUser.class)
            .block();
    }

    public User getCurrentUser(String userId) {
        return userRepository.findById(java.util.UUID.fromString(userId))
            .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public record GitHubUser(Long id, String login, String email, String avatarUrl) {}
    public record AuthResponse(String token, User user) {}
}
