# Backend Implementation Guide — Part 5: Middleware, DTOs, Utilities, Exception Handling

---

## 8. Middleware

### 8.1 JwtAuthenticationFilter.java

```java
package com.codebaseqa.middleware;

import com.codebaseqa.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtService.isTokenValid(token)) {
                String userId = jwtService.extractUserId(token);
                UUID userUUID = UUID.fromString(userId);

                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                        userUUID, null, Collections.emptyList());

                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/auth/github")
            || path.equals("/api/repos/webhook/github")
            || path.startsWith("/actuator");
    }
}
```

---

## 9. DTOs (Data Transfer Objects)

### 9.1 Request DTOs

```java
package com.codebaseqa.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ConnectRepoRequest {

    @NotBlank(message = "Repository name is required")
    @Pattern(regexp = "^[\\w.-]+/[\\w.-]+$",
             message = "Repository must be in 'owner/name' format")
    private String repoFullName;

    private String branch; // Optional, defaults to repo's default branch
}
```

```java
package com.codebaseqa.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class AskQuestionRequest {

    @NotNull(message = "Repository ID is required")
    private UUID repoId;

    private UUID conversationId; // Optional — null creates new conversation

    @NotBlank(message = "Question is required")
    @Size(max = 1000, message = "Question must be under 1000 characters")
    private String question;
}
```

### 9.2 Response DTOs

```java
package com.codebaseqa.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class RepoResponse {
    private UUID id;
    private String fullName;
    private String branch;
    private String status;
    private Integer totalChunks;
    private Instant lastIndexedAt;
    private Instant createdAt;
}
```

```java
package com.codebaseqa.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class IndexingStatusResponse {
    private UUID jobId;
    private String status;
    private Integer progress;
    private Integer processedFiles;
    private Integer totalFiles;
    private String errorMessage;
}
```

---

## 10. Exception Handling

### 10.1 Custom Exceptions

```java
package com.codebaseqa.exception;

import java.util.UUID;

public class RepoNotFoundException extends RuntimeException {
    public RepoNotFoundException(UUID repoId) {
        super("Repository not found: " + repoId);
    }
}
```

```java
package com.codebaseqa.exception;

import lombok.Getter;

@Getter
public class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
```

### 10.2 GlobalExceptionHandler.java

```java
package com.codebaseqa.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(RepoNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRepoNotFound(RepoNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "error", Map.of(
                "code", "REPO_NOT_FOUND",
                "message", e.getMessage()
            ),
            "timestamp", Instant.now()
        ));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("X-RateLimit-Remaining", "0")
            .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
            .body(Map.of(
                "error", Map.of(
                    "code", "RATE_LIMIT_EXCEEDED",
                    "message", e.getMessage(),
                    "retryAfter", e.getRetryAfterSeconds()
                ),
                "timestamp", Instant.now()
            ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "error", Map.of(
                "code", "CONFLICT",
                "message", e.getMessage()
            ),
            "timestamp", Instant.now()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String errors = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "error", Map.of(
                "code", "VALIDATION_ERROR",
                "message", errors
            ),
            "timestamp", Instant.now()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "error", Map.of(
                "code", "INTERNAL_ERROR",
                "message", "An unexpected error occurred"
            ),
            "timestamp", Instant.now()
        ));
    }
}
```

---

## 11. Utility Classes

### 11.1 GitHubClient.java

```java
package com.codebaseqa.util;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class GitHubClient {

    private final WebClient.Builder webClientBuilder;

    public record GitHubRepo(Long id, String fullName, String defaultBranch) {}

    /**
     * Get repository info from GitHub API.
     * Verifies the user has access to this repo.
     */
    public GitHubRepo getRepository(String fullName, String token) {
        Map<String, Object> response = webClientBuilder.build()
            .get()
            .uri("https://api.github.com/repos/" + fullName)
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .bodyToMono(Map.class)
            .block();

        return new GitHubRepo(
            ((Number) response.get("id")).longValue(),
            (String) response.get("full_name"),
            (String) response.get("default_branch")
        );
    }

    /**
     * Create a webhook on the repository for push events.
     */
    public Long createWebhook(String fullName, String token, String webhookUrl, String secret) {
        Map<String, Object> response = webClientBuilder.build()
            .post()
            .uri("https://api.github.com/repos/" + fullName + "/hooks")
            .header("Authorization", "Bearer " + token)
            .bodyValue(Map.of(
                "name", "web",
                "active", true,
                "events", new String[]{"push"},
                "config", Map.of(
                    "url", webhookUrl,
                    "content_type", "json",
                    "secret", secret
                )
            ))
            .retrieve()
            .bodyToMono(Map.class)
            .block();

        return ((Number) response.get("id")).longValue();
    }

    /**
     * Delete a webhook from the repository.
     */
    public void deleteWebhook(String fullName, Long webhookId, String token) {
        webClientBuilder.build()
            .delete()
            .uri("https://api.github.com/repos/" + fullName + "/hooks/" + webhookId)
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .toBodilessEntity()
            .block();
    }
}
```

### 11.2 WebhookController.java (GitHub Webhook Receiver)

```java
package com.codebaseqa.controller;

import com.codebaseqa.model.IndexingJob;
import com.codebaseqa.model.Repo;
import com.codebaseqa.repository.IndexingJobRepository;
import com.codebaseqa.repository.RepoRepository;
import com.codebaseqa.service.SqsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/repos/webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final RepoRepository repoRepository;
    private final IndexingJobRepository jobRepository;
    private final SqsService sqsService;
    private final ObjectMapper objectMapper;

    @Value("${app.github.webhook-secret:webhook-secret}")
    private String webhookSecret;

    @PostMapping("/github")
    public ResponseEntity<Map<String, Object>> handleGithubWebhook(
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestHeader("X-GitHub-Event") String event,
            @RequestBody String payload) {

        // 1. Verify webhook signature
        if (!verifySignature(payload, signature)) {
            return ResponseEntity.status(401).body(Map.of(
                "error", Map.of("message", "Invalid signature")));
        }

        // 2. Only process push events
        if (!"push".equals(event)) {
            return ResponseEntity.ok(Map.of("data", Map.of("message", "Event ignored")));
        }

        try {
            JsonNode json = objectMapper.readTree(payload);
            long githubRepoId = json.get("repository").get("id").asLong();

            // 3. Find the connected repo
            // Note: We need to find by github_repo_id across all users
            Optional<Repo> repoOpt = repoRepository.findAll().stream()
                .filter(r -> r.getGithubRepoId().equals(githubRepoId))
                .findFirst();

            if (repoOpt.isEmpty()) {
                return ResponseEntity.ok(Map.of("data", Map.of("message", "Repo not connected")));
            }

            Repo repo = repoOpt.get();

            // 4. Extract changed files from commits
            Set<String> changedFiles = new HashSet<>();
            JsonNode commits = json.get("commits");
            if (commits != null) {
                for (JsonNode commit : commits) {
                    addFiles(changedFiles, commit.get("added"));
                    addFiles(changedFiles, commit.get("modified"));
                    addFiles(changedFiles, commit.get("removed"));
                }
            }

            if (changedFiles.isEmpty()) {
                return ResponseEntity.ok(Map.of("data", Map.of("message", "No relevant changes")));
            }

            // 5. Create incremental indexing job
            IndexingJob job = jobRepository.save(IndexingJob.builder()
                .repo(repo)
                .status(IndexingJob.JobStatus.QUEUED)
                .jobType(IndexingJob.JobType.INCREMENTAL)
                .changedFiles(new ArrayList<>(changedFiles))
                .build());

            sqsService.sendIndexingMessage(job.getId(), repo.getId());

            log.info("Webhook processed: {} changed files for {}", changedFiles.size(), repo.getFullName());
            return ResponseEntity.ok(Map.of("data", Map.of(
                "message", "Webhook processed",
                "jobId", job.getId()
            )));

        } catch (Exception e) {
            log.error("Error processing webhook", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", Map.of("message", "Failed to process webhook")));
        }
    }

    private boolean verifySignature(String payload, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);
            return expected.equals(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private void addFiles(Set<String> files, JsonNode array) {
        if (array != null) {
            for (JsonNode file : array) {
                files.add(file.asText());
            }
        }
    }
}
```

---

## 12. Application Entry Point

### CodebaseQaApplication.java

```java
package com.codebaseqa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // Enables @Scheduled for SQS polling and stale job cleanup
public class CodebaseQaApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodebaseQaApplication.class, args);
    }
}
```

---

## 13. Key Implementation Notes

### pgvector with JPA

Since JPA doesn't natively support pgvector, the `embedding` field in `CodeChunk` is stored as a string. The native query in `ChunkRepository` handles the cast to `vector` type. When saving, format the embedding as `"[0.1,0.2,0.3,...]"`.

You may need a custom Hibernate type or use `@Column(columnDefinition = "vector(768)")` and handle serialization manually. An alternative is to use `jdbcTemplate` for insert operations on the chunks table.

### SSE with POST requests

Spring's `SseEmitter` works with any HTTP method. The frontend will use `fetch()` with `ReadableStream` to consume the SSE response from a POST endpoint (since `EventSource` only supports GET).

### Transaction boundaries

- The `IndexingService.processFullIndexing()` method is `@Transactional` — if embedding fails midway, all chunks for that run are rolled back.
- For very large repos, consider batching the save operations to avoid holding a transaction open too long.

### Thread safety

- `SseEmitter` is not thread-safe. The `QueryService` runs in a `CompletableFuture` — ensure only one thread writes to the emitter at a time.
- The SQS worker uses `@Scheduled(fixedDelay = 5000)` which ensures only one poll runs at a time (fixedDelay waits for completion before scheduling next).
