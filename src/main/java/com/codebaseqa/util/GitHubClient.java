package com.codebaseqa.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubClient {

    private final WebClient.Builder webClientBuilder;

    /**
     * Get repository information from GitHub.
     * Verifies that the user has access to the repository.
     */
    public GitHubRepository getRepository(String fullName, String token) {
        log.info("=== Fetching repository from GitHub ===");
        log.info("Repository: {}", fullName);
        log.info("Token present: {}", token != null && !token.isEmpty());
        log.info("Token length: {}", token != null ? token.length() : 0);
        log.info("Token prefix: {}", token != null && token.length() > 10 ? token.substring(0, 10) + "..." : "N/A");
        log.info("Making request to: https://api.github.com/repos/{}", fullName);
        
        try {
            Map<String, Object> response = webClientBuilder.build()
                .get()
                .uri("https://api.github.com/repos/" + fullName)  // Use string concatenation instead of path variable
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "CodebaseQA-App")  // GitHub requires User-Agent
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                throw new RuntimeException("Failed to fetch repository information");
            }

            log.info("Successfully fetched repository: {}", response.get("full_name"));
            return new GitHubRepository(
                ((Number) response.get("id")).longValue(),
                (String) response.get("full_name"),
                (String) response.get("default_branch")
            );
        } catch (WebClientResponseException.Forbidden e) {
            log.error("GitHub API returned 403 Forbidden for repository: {}", fullName);
            log.error("Response body: {}", e.getResponseBodyAsString());
            throw new RuntimeException("You don't have access to this repository");
        } catch (WebClientResponseException.NotFound e) {
            log.error("GitHub API returned 404 Not Found for repository: {}", fullName);
            log.error("Response body: {}", e.getResponseBodyAsString());
            log.error("Request headers were: Authorization=Bearer {}..., Accept=application/vnd.github.v3+json", 
                token != null && token.length() > 10 ? token.substring(0, 10) : "N/A");
            throw new RuntimeException("Repository not found");
        } catch (WebClientResponseException e) {
            log.error("GitHub API error (status {}): {}", e.getStatusCode(), e.getMessage());
            log.error("Response body: {}", e.getResponseBodyAsString());
            throw new RuntimeException("Failed to fetch repository information from GitHub");
        } catch (Exception e) {
            log.error("Failed to fetch repository from GitHub: {}", fullName, e);
            throw new RuntimeException("Failed to fetch repository information from GitHub");
        }
    }

    /**
     * Create a webhook on GitHub for push events.
     * Returns the webhook ID.
     */
    public Long createWebhook(String fullName, String webhookUrl, String secret, String token) {
        try {
            Map<String, Object> requestBody = Map.of(
                "name", "web",
                "active", true,
                "events", new String[]{"push"},
                "config", Map.of(
                    "url", webhookUrl,
                    "content_type", "json",
                    "secret", secret,
                    "insecure_ssl", "0"
                )
            );

            Map<String, Object> response = webClientBuilder.build()
                .post()
                .uri("https://api.github.com/repos/{fullName}/hooks", fullName)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                throw new RuntimeException("Failed to create webhook");
            }

            Long webhookId = ((Number) response.get("id")).longValue();
            log.info("Created webhook for {}: id={}", fullName, webhookId);
            return webhookId;
        } catch (Exception e) {
            log.error("Failed to create webhook for {}", fullName, e);
            throw new RuntimeException("Failed to create webhook on GitHub");
        }
    }

    /**
     * Delete a webhook from GitHub.
     */
    public void deleteWebhook(String fullName, Long webhookId, String token) {
        try {
            webClientBuilder.build()
                .delete()
                .uri("https://api.github.com/repos/{fullName}/hooks/{hookId}", fullName, webhookId)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .retrieve()
                .toBodilessEntity()
                .block();

            log.info("Deleted webhook for {}: id={}", fullName, webhookId);
        } catch (Exception e) {
            log.warn("Failed to delete webhook for {} (id={}): {}", fullName, webhookId, e.getMessage());
            // Don't throw — webhook deletion is best-effort
        }
    }

    public record GitHubRepository(Long id, String fullName, String defaultBranch) {}
}
