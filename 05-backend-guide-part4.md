# Backend Implementation Guide — Part 4: Query Service, Worker, Controllers

---

## 5.11 QueryService.java

The RAG pipeline orchestrator — the most important service for the Q&A feature.

```java
package com.codebaseqa.service;

import com.codebaseqa.model.Conversation;
import com.codebaseqa.model.Message;
import com.codebaseqa.model.Repo;
import com.codebaseqa.repository.ChunkRepository;
import com.codebaseqa.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueryService {

    private final EmbeddingService embeddingService;
    private final GeminiService geminiService;
    private final ChunkRepository chunkRepository;
    private final MessageRepository messageRepository;
    private final CacheService cacheService;
    private final RateLimitService rateLimitService;
    private final ConversationService conversationService;

    private static final int TOP_K_CHUNKS = 8;

    private static final String SYSTEM_PROMPT = """
        You are a code assistant that answers questions about a specific codebase.
        You will be given relevant code snippets from the repository along with the user's question.

        Rules:
        1. ONLY answer based on the provided code snippets. Do not make up information.
        2. Always reference the file path and line numbers when discussing code.
        3. Use markdown formatting with code blocks for code references.
        4. If the provided snippets don't contain enough information to answer, say so clearly.
        5. Be concise but thorough. Explain the logic, not just what the code does.
        6. When referencing code, use this format: `filepath:startLine-endLine`

        The code snippets below are from the repository. Use them to answer the question.
        """;

    /**
     * Process a question and stream the answer via SSE.
     */
    public void streamAnswer(UUID repoId, UUID userId, String question,
                             UUID conversationId, SseEmitter emitter) {
        try {
            // 1. Rate limit check
            rateLimitService.checkQueryRateLimit(userId);

            // 2. Check cache
            Optional<String> cached = cacheService.getCachedResult(repoId, question);
            if (cached.isPresent()) {
                sendCachedResponse(emitter, cached.get(), conversationId);
                return;
            }

            // 3. Get or create conversation
            Conversation conversation = conversationService
                .getOrCreateConversation(conversationId, userId, repoId, question);

            // 4. Get conversation history (last 10 messages for context)
            List<Message> history = messageRepository
                .findTop10ByConversationIdOrderByCreatedAtDesc(conversation.getId());
            Collections.reverse(history); // Oldest first

            // 5. Embed the question
            float[] queryEmbedding = embeddingService.embedText(question);
            String embeddingStr = embeddingService.toVectorString(queryEmbedding);

            // 6. Vector similarity search
            List<Object[]> similarChunks = chunkRepository
                .findSimilarChunks(repoId, embeddingStr, TOP_K_CHUNKS);

            // 7. Build citations and send them first
            List<Map<String, Object>> citations = buildCitations(similarChunks);
            sendSseEvent(emitter, "citations", citations);

            // 8. Build the prompt with retrieved context
            String contextPrompt = buildContextPrompt(similarChunks, question);

            // 9. Build conversation history for LLM
            List<Map<String, String>> conversationHistory = history.stream()
                .map(msg -> Map.of(
                    "role", msg.getRole().name(),
                    "content", msg.getContent()
                ))
                .toList();

            // 10. Stream LLM response
            StringBuilder fullResponse = new StringBuilder();
            String answer = geminiService.streamChat(
                SYSTEM_PROMPT,
                conversationHistory,
                contextPrompt,
                token -> {
                    fullResponse.append(token);
                    try {
                        sendSseEvent(emitter, "token", Map.of("content", token));
                    } catch (Exception e) {
                        log.warn("Failed to send SSE token", e);
                    }
                }
            );

            // 11. Save user message
            Message userMsg = conversationService.saveMessage(
                conversation, Message.MessageRole.user, question, null);

            // 12. Save assistant message with citations
            List<Message.Citation> msgCitations = citations.stream()
                .map(c -> new Message.Citation(
                    (String) c.get("filePath"),
                    (Integer) c.get("startLine"),
                    (Integer) c.get("endLine"),
                    (String) c.get("chunkName"),
                    ((String) c.get("snippet")).substring(0, Math.min(200,
                        ((String) c.get("snippet")).length()))
                ))
                .toList();

            Message assistantMsg = conversationService.saveMessage(
                conversation, Message.MessageRole.assistant, answer, msgCitations);

            // 13. Send done event
            sendSseEvent(emitter, "done", Map.of(
                "messageId", assistantMsg.getId(),
                "conversationId", conversation.getId(),
                "tokenCount", answer.length() / 4
            ));

            // 14. Cache the result
            cacheService.cacheQueryResult(repoId, question, answer);

            emitter.complete();

        } catch (Exception e) {
            log.error("Error streaming answer", e);
            try {
                sendSseEvent(emitter, "error", Map.of("message", e.getMessage()));
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        }
    }

    private String buildContextPrompt(List<Object[]> chunks, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Relevant Code Snippets\n\n");

        for (Object[] chunk : chunks) {
            String filePath = (String) chunk[1];
            int startLine = (int) chunk[2];
            int endLine = (int) chunk[3];
            String chunkName = (String) chunk[5];
            String content = (String) chunk[6];
            String language = (String) chunk[7];

            sb.append("### ").append(filePath)
              .append(" (lines ").append(startLine).append("-").append(endLine).append(")\n");
            if (chunkName != null) {
                sb.append("**").append(chunkName).append("**\n");
            }
            sb.append("```").append(language).append("\n");
            sb.append(content).append("\n");
            sb.append("```\n\n");
        }

        sb.append("## Question\n\n");
        sb.append(question);

        return sb.toString();
    }

    private List<Map<String, Object>> buildCitations(List<Object[]> chunks) {
        return chunks.stream().map(chunk -> {
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("filePath", chunk[1]);
            citation.put("startLine", chunk[2]);
            citation.put("endLine", chunk[3]);
            citation.put("chunkName", chunk[5]);
            citation.put("snippet", ((String) chunk[6]).substring(0,
                Math.min(300, ((String) chunk[6]).length())));
            citation.put("similarity", chunk[8]);
            return citation;
        }).toList();
    }

    private void sendCachedResponse(SseEmitter emitter, String cachedAnswer,
                                     UUID conversationId) throws IOException {
        sendSseEvent(emitter, "token", Map.of("content", cachedAnswer));
        sendSseEvent(emitter, "done", Map.of(
            "conversationId", conversationId,
            "cached", true
        ));
        emitter.complete();
    }

    private void sendSseEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event()
            .name(eventName)
            .data(data));
    }
}
```

---

## 5.12 ConversationService.java

```java
package com.codebaseqa.service;

import com.codebaseqa.model.Conversation;
import com.codebaseqa.model.Message;
import com.codebaseqa.model.Repo;
import com.codebaseqa.model.User;
import com.codebaseqa.repository.ConversationRepository;
import com.codebaseqa.repository.MessageRepository;
import com.codebaseqa.repository.RepoRepository;
import com.codebaseqa.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final RepoRepository repoRepository;

    public Conversation getOrCreateConversation(UUID conversationId, UUID userId,
                                                 UUID repoId, String firstQuestion) {
        if (conversationId != null) {
            return conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));
        }

        // Create new conversation with title from first question
        User user = userRepository.findById(userId).orElseThrow();
        Repo repo = repoRepository.findById(repoId).orElseThrow();

        String title = firstQuestion.length() > 100
            ? firstQuestion.substring(0, 100) + "..."
            : firstQuestion;

        return conversationRepository.save(Conversation.builder()
            .user(user)
            .repo(repo)
            .title(title)
            .build());
    }

    public Message saveMessage(Conversation conversation, Message.MessageRole role,
                               String content, List<Message.Citation> citations) {
        return messageRepository.save(Message.builder()
            .conversation(conversation)
            .role(role)
            .content(content)
            .citations(citations)
            .tokenCount(content.length() / 4)
            .build());
    }

    public Page<Conversation> getUserConversations(UUID userId, UUID repoId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        if (repoId != null) {
            return conversationRepository.findByUserIdAndRepoIdOrderByUpdatedAtDesc(
                userId, repoId, pageRequest);
        }
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId, pageRequest);
    }

    public Conversation getConversation(UUID conversationId, UUID userId) {
        return conversationRepository.findByIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new RuntimeException("Conversation not found"));
    }

    @Transactional
    public void deleteConversation(UUID conversationId, UUID userId) {
        Conversation conv = getConversation(conversationId, userId);
        conversationRepository.delete(conv);
    }
}
```

---

## 6. Worker Layer

### 6.1 IndexingWorker.java (SQS Consumer)

```java
package com.codebaseqa.worker;

import com.codebaseqa.model.IndexingJob;
import com.codebaseqa.repository.IndexingJobRepository;
import com.codebaseqa.service.IndexingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class IndexingWorker {

    private final SqsClient sqsClient;
    private final IndexingService indexingService;
    private final IndexingJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.aws.sqs.queue-url}")
    private String queueUrl;

    private static final int MAX_RETRIES = 3;

    /**
     * Poll SQS every 5 seconds for new indexing jobs.
     */
    @Scheduled(fixedDelay = 5000)
    public void pollMessages() {
        try {
            ReceiveMessageResponse response = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)       // Process one at a time
                    .waitTimeSeconds(5)           // Long polling (reduces API calls)
                    .visibilityTimeout(600)       // 10 min to process before retry
                    .build()
            );

            for (software.amazon.awssdk.services.sqs.model.Message message : response.messages()) {
                processMessage(message);
            }
        } catch (Exception e) {
            log.error("Error polling SQS", e);
        }
    }

    private void processMessage(software.amazon.awssdk.services.sqs.model.Message message) {
        try {
            JsonNode body = objectMapper.readTree(message.body());
            UUID jobId = UUID.fromString(body.get("jobId").asText());
            UUID repoId = UUID.fromString(body.get("repoId").asText());

            log.info("Processing indexing job: jobId={}, repoId={}", jobId, repoId);

            // Check if job should be retried
            IndexingJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                log.warn("Job not found, deleting message: {}", jobId);
                deleteMessage(message);
                return;
            }

            if (job.getAttempts() >= MAX_RETRIES) {
                log.error("Job exceeded max retries, moving to DLQ: {}", jobId);
                job.setStatus(IndexingJob.JobStatus.FAILED);
                job.setErrorMessage("Exceeded maximum retry attempts (" + MAX_RETRIES + ")");
                jobRepository.save(job);
                deleteMessage(message);
                return;
            }

            // Check if it's incremental or full
            if (job.getJobType() == IndexingJob.JobType.INCREMENTAL
                && job.getChangedFiles() != null) {
                indexingService.processIncrementalIndexing(jobId, repoId, job.getChangedFiles());
            } else {
                indexingService.processFullIndexing(jobId, repoId);
            }

            // Success — delete message from queue
            deleteMessage(message);
            log.info("Indexing job completed successfully: {}", jobId);

        } catch (Exception e) {
            log.error("Failed to process indexing message: {}", e.getMessage(), e);
            // Don't delete message — it will become visible again after visibility timeout
            // and be retried (or moved to DLQ by SQS after maxReceiveCount)
        }
    }

    private void deleteMessage(software.amazon.awssdk.services.sqs.model.Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(message.receiptHandle())
            .build());
    }
}
```

### 6.2 StaleJobCleanup.java (Scheduled Task)

```java
package com.codebaseqa.worker;

import com.codebaseqa.model.IndexingJob;
import com.codebaseqa.repository.IndexingJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class StaleJobCleanup {

    private final IndexingJobRepository jobRepository;

    /**
     * Every 5 minutes, check for jobs stuck in PROCESSING for more than 10 minutes.
     * Mark them as FAILED so they can be retried.
     */
    @Scheduled(fixedRate = 300_000) // 5 minutes
    public void cleanupStaleJobs() {
        Instant cutoff = Instant.now().minus(10, ChronoUnit.MINUTES);
        List<IndexingJob> staleJobs = jobRepository.findStaleJobs(cutoff);

        for (IndexingJob job : staleJobs) {
            log.warn("Marking stale job as FAILED: jobId={}, repoId={}",
                job.getId(), job.getRepo().getId());
            job.setStatus(IndexingJob.JobStatus.FAILED);
            job.setErrorMessage("Job timed out (stuck in PROCESSING for >10 minutes)");
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);
        }

        if (!staleJobs.isEmpty()) {
            log.info("Cleaned up {} stale jobs", staleJobs.size());
        }
    }
}
```

---

## 7. Controllers

### 7.1 AuthController.java

```java
package com.codebaseqa.controller;

import com.codebaseqa.model.User;
import com.codebaseqa.repository.UserRepository;
import com.codebaseqa.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    @Value("${app.github.client-id}")
    private String githubClientId;

    @Value("${app.github.redirect-uri}")
    private String redirectUri;

    @GetMapping("/github")
    public ResponseEntity<Void> redirectToGithub() {
        String url = "https://github.com/login/oauth/authorize"
            + "?client_id=" + githubClientId
            + "&redirect_uri=" + redirectUri
            + "&scope=repo,read:user,user:email";
        return ResponseEntity.status(302).location(URI.create(url)).build();
    }

    @GetMapping("/github/callback")
    public ResponseEntity<Map<String, Object>> githubCallback(@RequestParam String code) {
        AuthService.AuthResponse response = authService.authenticateWithGithub(code);
        User user = response.user();

        return ResponseEntity.ok(Map.of(
            "data", Map.of(
                "token", response.token(),
                "user", Map.of(
                    "id", user.getId(),
                    "username", user.getUsername(),
                    "email", user.getEmail() != null ? user.getEmail() : "",
                    "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : ""
                )
            )
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(
            @AuthenticationPrincipal UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return ResponseEntity.ok(Map.of(
            "data", Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "email", user.getEmail() != null ? user.getEmail() : "",
                "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                "createdAt", user.getCreatedAt()
            )
        ));
    }
}
```

### 7.2 RepoController.java

```java
package com.codebaseqa.controller;

import com.codebaseqa.dto.request.ConnectRepoRequest;
import com.codebaseqa.model.IndexingJob;
import com.codebaseqa.model.Repo;
import com.codebaseqa.repository.IndexingJobRepository;
import com.codebaseqa.service.RepoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
public class RepoController {

    private final RepoService repoService;
    private final IndexingJobRepository jobRepository;

    @PostMapping
    public ResponseEntity<Map<String, Object>> connectRepo(
            @Valid @RequestBody ConnectRepoRequest request,
            @AuthenticationPrincipal UUID userId) {
        // userId is extracted from JWT by the auth filter
        Repo repo = repoService.connectRepo(request,
            repoService.getUser(userId)); // helper to get User entity

        return ResponseEntity.accepted().body(Map.of(
            "data", Map.of(
                "id", repo.getId(),
                "fullName", repo.getFullName(),
                "branch", repo.getDefaultBranch(),
                "status", repo.getStatus()
            )
        ));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listRepos(
            @AuthenticationPrincipal UUID userId) {
        List<Repo> repos = repoService.getUserRepos(userId);
        List<Map<String, Object>> repoList = repos.stream().map(r -> Map.<String, Object>of(
            "id", r.getId(),
            "fullName", r.getFullName(),
            "branch", r.getDefaultBranch(),
            "status", r.getStatus(),
            "totalChunks", r.getTotalChunks() != null ? r.getTotalChunks() : 0,
            "lastIndexedAt", r.getLastIndexedAt() != null ? r.getLastIndexedAt().toString() : ""
        )).toList();

        return ResponseEntity.ok(Map.of("data", repoList));
    }

    @DeleteMapping("/{repoId}")
    public ResponseEntity<Map<String, Object>> disconnectRepo(
            @PathVariable UUID repoId,
            @AuthenticationPrincipal UUID userId) {
        repoService.disconnectRepo(repoId, userId);
        return ResponseEntity.ok(Map.of("data", Map.of("message", "Repository disconnected")));
    }

    @PostMapping("/{repoId}/reindex")
    public ResponseEntity<Map<String, Object>> reindex(
            @PathVariable UUID repoId,
            @AuthenticationPrincipal UUID userId) {
        IndexingJob job = repoService.triggerReindex(repoId, userId);
        return ResponseEntity.accepted().body(Map.of(
            "data", Map.of("jobId", job.getId(), "status", job.getStatus())
        ));
    }
}
```

### 7.3 QueryController.java

```java
package com.codebaseqa.controller;

import com.codebaseqa.dto.request.AskQuestionRequest;
import com.codebaseqa.service.QueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askQuestion(
            @Valid @RequestBody AskQuestionRequest request,
            @AuthenticationPrincipal UUID userId) {

        SseEmitter emitter = new SseEmitter(120_000L); // 2 min timeout

        CompletableFuture.runAsync(() -> {
            queryService.streamAnswer(
                request.getRepoId(),
                userId,
                request.getQuestion(),
                request.getConversationId(),
                emitter
            );
        });

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitter.complete());

        return emitter;
    }
}
```

### 7.4 ConversationController.java

```java
package com.codebaseqa.controller;

import com.codebaseqa.model.Conversation;
import com.codebaseqa.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> listConversations(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID repoId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<Conversation> conversations = conversationService
            .getUserConversations(userId, repoId, page, Math.min(size, 50));

        return ResponseEntity.ok(Map.of("data", Map.of(
            "conversations", conversations.getContent(),
            "totalCount", conversations.getTotalElements(),
            "page", page,
            "size", size
        )));
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> getConversation(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal UUID userId) {

        Conversation conv = conversationService.getConversation(conversationId, userId);
        return ResponseEntity.ok(Map.of("data", conv));
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> deleteConversation(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal UUID userId) {

        conversationService.deleteConversation(conversationId, userId);
        return ResponseEntity.ok(Map.of("data", Map.of("message", "Conversation deleted")));
    }
}
```
