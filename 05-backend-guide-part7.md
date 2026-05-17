# Backend Implementation Guide — Part 7: Design Patterns (Continued)

---

## 4. Service Implementations (impl/)

### 4.1 DefaultChunkingService.java

Uses the Strategy pattern — delegates to the appropriate LanguageChunker.

```java
package com.codebaseqa.service.impl;

import com.codebaseqa.service.ChunkingService;
import com.codebaseqa.service.chunking.LanguageChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultChunkingService implements ChunkingService {

    // Spring auto-injects all LanguageChunker implementations
    private final List<LanguageChunker> chunkers;

    private static final int MIN_CHUNK_TOKENS = 50;
    private static final int CHARS_PER_TOKEN = 4;

    @Override
    public List<CodeChunkResult> chunkFile(String content, String filePath, String language) {
        // Strategy selection — find the right chunker for this language
        LanguageChunker chunker = chunkers.stream()
            .filter(c -> c.supports(language))
            .findFirst()
            .orElse(chunkers.get(chunkers.size() - 1)); // FallbackChunker is last (due to @Order)

        log.debug("Using {} for language: {}", chunker.getClass().getSimpleName(), language);

        List<CodeChunkResult> chunks = chunker.chunk(content);

        // Filter out chunks that are too small
        chunks = chunks.stream()
            .filter(c -> c.content().length() / CHARS_PER_TOKEN >= MIN_CHUNK_TOKENS)
            .toList();

        // If no valid chunks, fall back to block chunking
        if (chunks.isEmpty()) {
            LanguageChunker fallback = chunkers.get(chunkers.size() - 1);
            chunks = fallback.chunk(content);
        }

        return chunks;
    }

    @Override
    public String detectLanguage(String filePath) {
        String ext = filePath.substring(filePath.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "java" -> "java";
            case "ts", "tsx" -> "typescript";
            case "js", "jsx" -> "javascript";
            case "py" -> "python";
            case "go" -> "go";
            case "rs" -> "rust";
            case "rb" -> "ruby";
            case "php" -> "php";
            case "cs" -> "csharp";
            case "kt" -> "kotlin";
            case "swift" -> "swift";
            case "scala" -> "scala";
            case "cpp", "cc", "cxx" -> "cpp";
            case "c", "h" -> "c";
            default -> "unknown";
        };
    }
}
```

### 4.2 GeminiEmbeddingService.java

```java
package com.codebaseqa.service.impl;

import com.codebaseqa.service.EmbeddingService;
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
public class GeminiEmbeddingService implements EmbeddingService {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;

    @Value("${app.gemini.api-key}")
    private String apiKey;

    @Value("${app.gemini.embedding-model}")
    private String embeddingModel;

    public GeminiEmbeddingService(WebClient.Builder webClientBuilder, CircuitBreakerRegistry registry) {
        this.webClient = webClientBuilder
            .baseUrl("https://generativelanguage.googleapis.com")
            .build();
        this.circuitBreaker = registry.circuitBreaker("gemini-embedding");
    }

    @Override
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

    @Override
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

    @Override
    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public int getDimension() {
        return 768; // Gemini text-embedding-004 outputs 768 dimensions
    }
}
```

### 4.3 GeminiLlmService.java

```java
package com.codebaseqa.service.impl;

import com.codebaseqa.service.LlmService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
@Slf4j
public class GeminiLlmService implements LlmService {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;

    @Value("${app.gemini.api-key}")
    private String apiKey;

    @Value("${app.gemini.chat-model}")
    private String chatModel;

    public GeminiLlmService(WebClient.Builder webClientBuilder, CircuitBreakerRegistry registry) {
        this.webClient = webClientBuilder
            .baseUrl("https://generativelanguage.googleapis.com")
            .build();
        this.circuitBreaker = registry.circuitBreaker("gemini-chat");
    }

    @Override
    public String streamChat(String systemPrompt,
                             List<Map<String, String>> conversationHistory,
                             String userMessage,
                             Consumer<String> tokenConsumer) {

        return circuitBreaker.executeSupplier(() -> {
            List<Map<String, Object>> contents = new ArrayList<>();

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

---

## 5. Builder Pattern — PromptBuilder

```java
package com.codebaseqa.service.prompt;

import com.codebaseqa.model.CodeChunk;
import com.codebaseqa.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder pattern for constructing LLM prompts.
 * Assembles system instructions, retrieved code context, conversation history,
 * and the user question into a properly formatted prompt.
 */
public class PromptBuilder {

    private String systemPrompt = "";
    private final List<ChunkContext> codeChunks = new ArrayList<>();
    private final List<MessageContext> history = new ArrayList<>();
    private String question = "";

    public static PromptBuilder create() {
        return new PromptBuilder();
    }

    public PromptBuilder withSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }

    public PromptBuilder withCodeChunk(String filePath, int startLine, int endLine,
                                        String chunkName, String content, String language) {
        this.codeChunks.add(new ChunkContext(filePath, startLine, endLine, chunkName, content, language));
        return this;
    }

    public PromptBuilder withCodeChunks(List<Object[]> rawChunks) {
        for (Object[] chunk : rawChunks) {
            this.codeChunks.add(new ChunkContext(
                (String) chunk[1],   // filePath
                (int) chunk[2],      // startLine
                (int) chunk[3],      // endLine
                (String) chunk[5],   // chunkName
                (String) chunk[6],   // content
                (String) chunk[7]    // language
            ));
        }
        return this;
    }

    public PromptBuilder withHistory(List<Message> messages) {
        for (Message msg : messages) {
            this.history.add(new MessageContext(msg.getRole().name(), msg.getContent()));
        }
        return this;
    }

    public PromptBuilder withQuestion(String question) {
        this.question = question;
        return this;
    }

    /**
     * Build the final user message that includes code context + question.
     * The system prompt is returned separately for the LLM API call.
     */
    public String buildUserMessage() {
        StringBuilder sb = new StringBuilder();

        // Code context section
        if (!codeChunks.isEmpty()) {
            sb.append("## Relevant Code Snippets\n\n");
            for (ChunkContext chunk : codeChunks) {
                sb.append("### ").append(chunk.filePath)
                  .append(" (lines ").append(chunk.startLine)
                  .append("-").append(chunk.endLine).append(")\n");
                if (chunk.chunkName != null) {
                    sb.append("**").append(chunk.chunkName).append("**\n");
                }
                sb.append("```").append(chunk.language).append("\n");
                sb.append(chunk.content).append("\n");
                sb.append("```\n\n");
            }
        }

        // Question section
        sb.append("## Question\n\n");
        sb.append(question);

        return sb.toString();
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<MessageContext> getHistory() {
        return history;
    }

    // Internal DTOs
    private record ChunkContext(String filePath, int startLine, int endLine,
                                 String chunkName, String content, String language) {}

    public record MessageContext(String role, String content) {}
}
```

---

## 6. How QueryService Uses These Patterns

The `QueryService` now uses interfaces and the builder:

```java
// In QueryService.java — key changes:

@Service
@RequiredArgsConstructor
public class QueryService {

    private final EmbeddingService embeddingService;   // ← Interface, not GeminiEmbeddingService
    private final LlmService llmService;               // ← Interface, not GeminiLlmService
    private final ChunkRepository chunkRepository;
    private final CacheService cacheService;
    private final RateLimitService rateLimitService;
    private final ConversationService conversationService;

    // ... in streamAnswer():

    // Step 5: Embed the question
    float[] queryEmbedding = embeddingService.embedText(question);
    String embeddingStr = embeddingService.toVectorString(queryEmbedding);

    // Step 6: Vector search
    List<Object[]> similarChunks = chunkRepository.findSimilarChunks(repoId, embeddingStr, TOP_K_CHUNKS);

    // Step 8: Build prompt using Builder pattern
    String userMessage = PromptBuilder.create()
        .withSystemPrompt(SYSTEM_PROMPT)
        .withCodeChunks(similarChunks)
        .withQuestion(question)
        .buildUserMessage();

    // Step 9: Stream LLM response using interface
    String answer = llmService.streamChat(
        SYSTEM_PROMPT,
        conversationHistory,
        userMessage,
        tokenConsumer
    );
}
```

---

## 7. Interview Talking Points

**"What design patterns did you use?"**

> "I used the **Strategy pattern** for language-specific code chunking — each language (Java, TypeScript, Python) has its own chunker that implements a common `LanguageChunker` interface. Spring auto-injects all implementations, and the `ChunkingService` selects the right strategy based on file extension. Adding a new language is just creating a new `@Component` — no existing code changes.
>
> I used **interface-based design** for the LLM and embedding services. `QueryService` depends on `LlmService` and `EmbeddingService` interfaces, not concrete Gemini classes. This means we could swap to OpenAI or a local model by just adding a new implementation and changing a config property — the query pipeline doesn't change at all.
>
> I used the **Builder pattern** for prompt construction because the prompt has multiple optional sections (system instructions, code context, conversation history, user question) that need to be assembled in a specific format. The builder makes this readable and testable."

**"Why not use interfaces for everything?"**

> "I only created interfaces where there's a real reason for polymorphism — LLM providers, embedding providers, and chunking strategies. Services like `AuthService` or `CacheService` have exactly one implementation and no reason to be swapped, so adding an interface there would be unnecessary abstraction."
