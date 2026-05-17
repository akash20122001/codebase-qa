# Backend Implementation Guide — Part 2: Services

---

## 5. Service Layer Implementation

### 5.1 AuthService.java

Handles GitHub OAuth flow: exchange code for token, fetch user profile, create/update user, issue JWT.

```java
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

        return new AuthResponse(jwt, user);
    }

    private String exchangeCodeForToken(String code) {
        Map<String, String> response = webClientBuilder.build()
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

        return response.get("access_token");
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

    public record GitHubUser(Long id, String login, String email, String avatarUrl) {}
    public record AuthResponse(String token, User user) {}
}
```

### 5.2 JwtService.java

```java
package com.codebaseqa.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    public String generateToken(String userId) {
        return Jwts.builder()
            .subject(userId)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(getSigningKey())
            .compact();
    }

    public String extractUserId(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
        return claims.getSubject();
    }

    public boolean isTokenValid(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
```

### 5.3 RepoService.java

```java
package com.codebaseqa.service;

import com.codebaseqa.dto.request.ConnectRepoRequest;
import com.codebaseqa.exception.RepoNotFoundException;
import com.codebaseqa.model.IndexingJob;
import com.codebaseqa.model.Repo;
import com.codebaseqa.model.User;
import com.codebaseqa.repository.IndexingJobRepository;
import com.codebaseqa.repository.RepoRepository;
import com.codebaseqa.util.GitHubClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RepoService {

    private final RepoRepository repoRepository;
    private final IndexingJobRepository jobRepository;
    private final GitHubClient gitHubClient;
    private final SqsService sqsService;

    @Transactional
    public Repo connectRepo(ConnectRepoRequest request, User user) {
        // 1. Verify user has access to this repo on GitHub
        var ghRepo = gitHubClient.getRepository(request.getRepoFullName(), user.getGithubToken());

        // 2. Check if already connected
        repoRepository.findByUserIdAndGithubRepoId(user.getId(), ghRepo.id())
            .ifPresent(r -> {
                throw new IllegalStateException("Repository already connected");
            });

        // 3. Save repo
        Repo repo = repoRepository.save(Repo.builder()
            .user(user)
            .githubRepoId(ghRepo.id())
            .fullName(request.getRepoFullName())
            .defaultBranch(request.getBranch() != null ? request.getBranch() : ghRepo.defaultBranch())
            .status(Repo.RepoStatus.PENDING)
            .build());

        // 4. Create indexing job
        IndexingJob job = jobRepository.save(IndexingJob.builder()
            .repo(repo)
            .status(IndexingJob.JobStatus.QUEUED)
            .jobType(IndexingJob.JobType.FULL)
            .build());

        // 5. Send message to SQS
        sqsService.sendIndexingMessage(job.getId(), repo.getId());

        log.info("Repo connected and indexing queued: {} (jobId={})", repo.getFullName(), job.getId());
        return repo;
    }

    public List<Repo> getUserRepos(UUID userId) {
        return repoRepository.findByUserId(userId);
    }

    public Repo getRepo(UUID repoId, UUID userId) {
        return repoRepository.findByIdAndUserId(repoId, userId)
            .orElseThrow(() -> new RepoNotFoundException(repoId));
    }

    @Transactional
    public void disconnectRepo(UUID repoId, UUID userId) {
        Repo repo = getRepo(repoId, userId);

        // Remove webhook from GitHub if exists
        if (repo.getWebhookId() != null) {
            gitHubClient.deleteWebhook(repo.getFullName(), repo.getWebhookId(),
                repo.getUser().getGithubToken());
        }

        // CASCADE delete handles chunks, conversations, jobs
        repoRepository.delete(repo);
        log.info("Repo disconnected: {}", repo.getFullName());
    }

    @Transactional
    public IndexingJob triggerReindex(UUID repoId, UUID userId) {
        Repo repo = getRepo(repoId, userId);

        // Check if already indexing
        boolean alreadyIndexing = jobRepository.existsByRepoIdAndStatusIn(
            repoId, List.of(IndexingJob.JobStatus.QUEUED, IndexingJob.JobStatus.PROCESSING));
        if (alreadyIndexing) {
            throw new IllegalStateException("Indexing already in progress");
        }

        IndexingJob job = jobRepository.save(IndexingJob.builder()
            .repo(repo)
            .status(IndexingJob.JobStatus.QUEUED)
            .jobType(IndexingJob.JobType.FULL)
            .build());

        sqsService.sendIndexingMessage(job.getId(), repo.getId());
        return job;
    }
}
```

### 5.4 SqsService.java

```java
package com.codebaseqa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SqsService {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    @Value("${app.aws.sqs.queue-url}")
    private String queueUrl;

    public void sendIndexingMessage(UUID jobId, UUID repoId) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "jobId", jobId.toString(),
                "repoId", repoId.toString()
            ));

            sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build());

            log.info("Sent indexing message to SQS: jobId={}, repoId={}", jobId, repoId);
        } catch (Exception e) {
            log.error("Failed to send SQS message", e);
            throw new RuntimeException("Failed to queue indexing job", e);
        }
    }
}
```

### 5.5 EmbeddingService.java

```java
package com.codebaseqa.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EmbeddingService {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;

    @Value("${app.gemini.api-key}")
    private String apiKey;

    @Value("${app.gemini.embedding-model}")
    private String embeddingModel;

    public EmbeddingService(WebClient.Builder webClientBuilder, CircuitBreakerRegistry registry) {
        this.webClient = webClientBuilder
            .baseUrl("https://generativelanguage.googleapis.com")
            .build();
        this.circuitBreaker = registry.circuitBreaker("gemini-embedding");
    }

    /**
     * Generate embedding for a single text.
     * Returns a float array of 768 dimensions.
     */
    public float[] embedText(String text) {
        return circuitBreaker.executeSupplier(() -> {
            Map<String, Object> response = webClient.post()
                .uri("/v1beta/models/{model}:embedContent?key={key}", embeddingModel, apiKey)
                .bodyValue(Map.of(
                    "model", "models/" + embeddingModel,
                    "content", Map.of("parts", List.of(Map.of("text", text)))
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            Map<String, Object> embedding = (Map<String, Object>) response.get("embedding");
            List<Double> values = (List<Double>) embedding.get("values");

            float[] result = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                result[i] = values.get(i).floatValue();
            }
            return result;
        });
    }

    /**
     * Batch embed multiple texts.
     * Gemini supports batch embedding — more efficient than individual calls.
     */
    public List<float[]> embedBatch(List<String> texts) {
        return circuitBreaker.executeSupplier(() -> {
            List<Map<String, Object>> requests = texts.stream()
                .map(text -> Map.<String, Object>of(
                    "model", "models/" + embeddingModel,
                    "content", Map.of("parts", List.of(Map.of("text", text)))
                ))
                .toList();

            Map<String, Object> response = webClient.post()
                .uri("/v1beta/models/{model}:batchEmbedContents?key={key}", embeddingModel, apiKey)
                .bodyValue(Map.of("requests", requests))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            List<Map<String, Object>> embeddings = (List<Map<String, Object>>) response.get("embeddings");

            return embeddings.stream().map(emb -> {
                List<Double> values = (List<Double>) emb.get("values");
                float[] result = new float[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    result[i] = values.get(i).floatValue();
                }
                return result;
            }).toList();
        });
    }

    /**
     * Convert float array to pgvector string format: "[0.1,0.2,0.3,...]"
     */
    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
```

### 5.6 GeminiService.java (LLM Chat with Streaming)

```java
package com.codebaseqa.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
@Slf4j
public class GeminiService {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;

    @Value("${app.gemini.api-key}")
    private String apiKey;

    @Value("${app.gemini.chat-model}")
    private String chatModel;

    public GeminiService(WebClient.Builder webClientBuilder, CircuitBreakerRegistry registry) {
        this.webClient = webClientBuilder
            .baseUrl("https://generativelanguage.googleapis.com")
            .build();
        this.circuitBreaker = registry.circuitBreaker("gemini-chat");
    }

    /**
     * Stream a chat response from Gemini.
     * Calls the tokenConsumer for each token received.
     * Returns the full accumulated response.
     */
    public String streamChat(String systemPrompt, List<Map<String, String>> conversationHistory,
                             String userMessage, Consumer<String> tokenConsumer) {

        return circuitBreaker.executeSupplier(() -> {
            // Build contents array for Gemini API
            List<Map<String, Object>> contents = new java.util.ArrayList<>();

            // Add conversation history
            for (Map<String, String> msg : conversationHistory) {
                contents.add(Map.of(
                    "role", msg.get("role").equals("user") ? "user" : "model",
                    "parts", List.of(Map.of("text", msg.get("content")))
                ));
            }

            // Add current user message
            contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userMessage))
            ));

            Map<String, Object> requestBody = Map.of(
                "contents", contents,
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "generationConfig", Map.of(
                    "temperature", 0.3,
                    "maxOutputTokens", 2048
                )
            );

            // Use streaming endpoint
            Flux<Map> responseFlux = webClient.post()
                .uri("/v1beta/models/{model}:streamGenerateContent?alt=sse&key={key}",
                    chatModel, apiKey)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(Map.class);

            StringBuilder fullResponse = new StringBuilder();

            responseFlux.toStream().forEach(chunk -> {
                try {
                    List<Map<String, Object>> candidates =
                        (List<Map<String, Object>>) chunk.get("candidates");
                    if (candidates != null && !candidates.isEmpty()) {
                        Map<String, Object> content =
                            (Map<String, Object>) candidates.get(0).get("content");
                        List<Map<String, Object>> parts =
                            (List<Map<String, Object>>) content.get("parts");
                        if (parts != null && !parts.isEmpty()) {
                            String text = (String) parts.get(0).get("text");
                            if (text != null) {
                                fullResponse.append(text);
                                tokenConsumer.accept(text);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Error parsing stream chunk", e);
                }
            });

            return fullResponse.toString();
        });
    }
}
```

### 5.7 CacheService.java

```java
package com.codebaseqa.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    /**
     * Cache a query result.
     * Key = hash of (repoId + question)
     */
    public void cacheQueryResult(UUID repoId, String question, String answer) {
        String key = buildCacheKey(repoId, question);
        redisTemplate.opsForValue().set(key, answer, CACHE_TTL);
        log.debug("Cached query result: {}", key);
    }

    /**
     * Get cached query result.
     */
    public Optional<String> getCachedResult(UUID repoId, String question) {
        String key = buildCacheKey(repoId, question);
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.debug("Cache hit: {}", key);
            return Optional.of((String) cached);
        }
        return Optional.empty();
    }

    /**
     * Invalidate all cached results for a repo (called after re-indexing).
     */
    public void invalidateRepoCache(UUID repoId) {
        // Use a pattern to delete all keys for this repo
        var keys = redisTemplate.keys("query:" + repoId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("Invalidated {} cached queries for repo {}", keys.size(), repoId);
        }
    }

    private String buildCacheKey(UUID repoId, String question) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(question.toLowerCase().trim().getBytes());
            String questionHash = HexFormat.of().formatHex(hash).substring(0, 16);
            return "query:" + repoId + ":" + questionHash;
        } catch (Exception e) {
            return "query:" + repoId + ":" + question.hashCode();
        }
    }
}
```

### 5.8 RateLimitService.java

```java
package com.codebaseqa.service;

import com.codebaseqa.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.rate-limit.queries-per-hour}")
    private int queriesPerHour;

    /**
     * Check and consume a rate limit token.
     * Uses a simple sliding window counter in Redis.
     * Throws RateLimitExceededException if limit exceeded.
     */
    public void checkQueryRateLimit(UUID userId) {
        String key = "ratelimit:query:" + userId;

        Long currentCount = redisTemplate.opsForValue().increment(key);

        if (currentCount == 1) {
            // First request in this window — set expiry
            redisTemplate.expire(key, Duration.ofHours(1));
        }

        if (currentCount > queriesPerHour) {
            Long ttl = redisTemplate.getExpire(key);
            throw new RateLimitExceededException(
                "Query rate limit exceeded. You can make " + queriesPerHour + " queries per hour.",
                ttl != null ? ttl : 3600
            );
        }

        log.debug("Rate limit check passed for user {}: {}/{}", userId, currentCount, queriesPerHour);
    }

    /**
     * Get remaining quota for a user.
     */
    public int getRemainingQuota(UUID userId) {
        String key = "ratelimit:query:" + userId;
        Object count = redisTemplate.opsForValue().get(key);
        int used = count != null ? Integer.parseInt(count.toString()) : 0;
        return Math.max(0, queriesPerHour - used);
    }
}
```
