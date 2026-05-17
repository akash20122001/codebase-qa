# Backend Implementation Guide

---

## Overview

This guide provides step-by-step instructions for implementing the Spring Boot backend. Follow the sections in order — each builds on the previous one.

---

## 1. Project Setup

### 1.1 Initialize Spring Boot Project

Use [Spring Initializr](https://start.spring.io/) with these settings:

- **Project:** Maven
- **Language:** Java 17
- **Spring Boot:** 3.2.x
- **Group:** com.codebaseqa
- **Artifact:** backend
- **Packaging:** Jar

**Dependencies to select:**
- Spring Web
- Spring Data JPA
- Spring Security
- OAuth2 Client
- PostgreSQL Driver
- Flyway Migration
- Spring Data Redis
- Validation (Bean Validation)
- Lombok

### 1.2 Additional Maven Dependencies (add to pom.xml)

```xml
<dependencies>
    <!-- Already from Initializr -->
    <!-- Add these manually: -->

    <!-- AWS SQS SDK v2 -->
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>sqs</artifactId>
        <version>2.21.0</version>
    </dependency>

    <!-- JGit for Git operations -->
    <dependency>
        <groupId>org.eclipse.jgit</groupId>
        <artifactId>org.eclipse.jgit</artifactId>
        <version>6.7.0.202309050840-r</version>
    </dependency>

    <!-- Resilience4j for circuit breaker -->
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-spring-boot3</artifactId>
        <version>2.1.0</version>
    </dependency>

    <!-- JWT -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.3</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>

    <!-- WebClient for calling Gemini API -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>

    <!-- MapStruct for DTO mapping -->
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>1.5.5.Final</version>
    </dependency>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct-processor</artifactId>
        <version>1.5.5.Final</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### 1.3 Application Configuration (application.yml)

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:codebaseqa}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      url: ${REDIS_URL:redis://localhost:6379}

app:
  jwt:
    secret: ${JWT_SECRET:your-256-bit-secret-key-here-min-32-chars}
    expiration-ms: 86400000  # 24 hours
  github:
    client-id: ${GITHUB_CLIENT_ID}
    client-secret: ${GITHUB_CLIENT_SECRET}
    redirect-uri: ${GITHUB_REDIRECT_URI:http://localhost:8080/api/auth/github/callback}
  gemini:
    api-key: ${GEMINI_API_KEY}
    embedding-model: text-embedding-004
    chat-model: gemini-1.5-flash
  aws:
    region: ${AWS_REGION:us-east-1}
    sqs:
      queue-url: ${SQS_QUEUE_URL}
      dlq-url: ${SQS_DLQ_URL}
  rate-limit:
    queries-per-hour: 20
    repos-per-hour: 5
  indexing:
    temp-dir: ${INDEXING_TEMP_DIR:/tmp/codebase-qa}
    max-file-size-kb: 500
    supported-extensions: .java,.ts,.tsx,.js,.jsx,.py,.go,.rs,.cpp,.c,.h,.rb,.php,.cs,.kt,.swift,.scala
    excluded-dirs: node_modules,.git,dist,build,target,.idea,.vscode,vendor,__pycache__
```

---

## 2. Configuration Classes

### 2.1 SecurityConfig.java

```java
package com.codebaseqa.config;

import com.codebaseqa.middleware.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/github", "/api/auth/github/callback").permitAll()
                .requestMatchers("/api/repos/webhook/github").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
            "http://localhost:5173",  // Vite dev server
            "${FRONTEND_URL:https://your-app.vercel.app}"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

### 2.2 SqsConfig.java

```java
package com.codebaseqa.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class SqsConfig {

    @Value("${app.aws.region}")
    private String region;

    @Bean
    public SqsClient sqsClient() {
        return SqsClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }
}
```

### 2.3 RedisConfig.java

```java
package com.codebaseqa.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
```

### 2.4 ResilienceConfig.java

```java
package com.codebaseqa.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)                     // Open after 50% failures
            .waitDurationInOpenState(Duration.ofSeconds(30)) // Wait 30s before half-open
            .slidingWindowSize(10)                         // Last 10 calls
            .minimumNumberOfCalls(5)                       // Need at least 5 calls
            .build();

        return CircuitBreakerRegistry.of(config);
    }
}
```

---

## 3. JPA Entities (Model Layer)

### 3.1 User.java

```java
package com.codebaseqa.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "github_id", unique = true, nullable = false)
    private Long githubId;

    @Column(nullable = false)
    private String username;

    private String email;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "github_token", nullable = false)
    private String githubToken;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

### 3.2 Repo.java

```java
package com.codebaseqa.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repos")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Repo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "github_repo_id", nullable = false)
    private Long githubRepoId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RepoStatus status;

    @Column(name = "last_indexed_at")
    private Instant lastIndexedAt;

    @Column(name = "webhook_id")
    private Long webhookId;

    @Column(name = "total_chunks")
    private Integer totalChunks = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum RepoStatus {
        PENDING, INDEXING, READY, FAILED
    }
}
```

### 3.3 CodeChunk.java

```java
package com.codebaseqa.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "code_chunks")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CodeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false)
    private Repo repo;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "start_line", nullable = false)
    private Integer startLine;

    @Column(name = "end_line", nullable = false)
    private Integer endLine;

    @Column(name = "chunk_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private ChunkType chunkType;

    @Column(name = "chunk_name")
    private String chunkName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private String language;

    // Stored as string "[0.1, 0.2, ...]" — pgvector handles the cast in queries
    @Column(nullable = false, columnDefinition = "vector(768)")
    private String embedding;

    @Column(name = "token_count", nullable = false)
    private Integer tokenCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum ChunkType {
        FUNCTION, CLASS, METHOD, MODULE, BLOCK, INTERFACE, ENUM
    }
}
```

### 3.4 Conversation.java

```java
package com.codebaseqa.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "conversations")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false)
    private Repo repo;

    @Column(nullable = false)
    private String title;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

### 3.5 Message.java

```java
package com.codebaseqa.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "messages")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Citation> citations;

    @Column(name = "token_count")
    private Integer tokenCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum MessageRole {
        user, assistant
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Citation {
        private String filePath;
        private Integer startLine;
        private Integer endLine;
        private String chunkName;
        private String snippet;
    }
}
```

### 3.6 IndexingJob.java

```java
package com.codebaseqa.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "indexing_jobs")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class IndexingJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false)
    private Repo repo;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private JobStatus status;

    @Column(name = "job_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private JobType jobType;

    @Column
    private Integer progress = 0;

    @Column(name = "total_files")
    private Integer totalFiles = 0;

    @Column(name = "processed_files")
    private Integer processedFiles = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column
    private Integer attempts = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_files", columnDefinition = "jsonb")
    private List<String> changedFiles;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum JobStatus {
        QUEUED, PROCESSING, COMPLETED, FAILED
    }

    public enum JobType {
        FULL, INCREMENTAL
    }
}
```

---

## 4. Repository Layer (Data Access)

### 4.1 UserRepository.java

```java
package com.codebaseqa.repository;

import com.codebaseqa.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByGithubId(Long githubId);
}
```

### 4.2 RepoRepository.java

```java
package com.codebaseqa.repository;

import com.codebaseqa.model.Repo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepoRepository extends JpaRepository<Repo, UUID> {
    List<Repo> findByUserId(UUID userId);
    Optional<Repo> findByUserIdAndGithubRepoId(UUID userId, Long githubRepoId);
    Optional<Repo> findByIdAndUserId(UUID id, UUID userId);
}
```

### 4.3 ChunkRepository.java

```java
package com.codebaseqa.repository;

import com.codebaseqa.model.CodeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ChunkRepository extends JpaRepository<CodeChunk, UUID> {

    /**
     * Vector similarity search — finds the most relevant code chunks for a query.
     * Returns raw Object[] because pgvector operations aren't natively supported by JPA.
     */
    @Query(value = """
        SELECT c.id, c.file_path, c.start_line, c.end_line,
               c.chunk_type, c.chunk_name, c.content, c.language,
               1 - (c.embedding <=> CAST(:embedding AS vector)) AS similarity
        FROM code_chunks c
        WHERE c.repo_id = :repoId
        ORDER BY c.embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findSimilarChunks(
        @Param("repoId") UUID repoId,
        @Param("embedding") String embedding,
        @Param("limit") int limit
    );

    @Modifying
    @Query("DELETE FROM CodeChunk c WHERE c.repo.id = :repoId")
    void deleteByRepoId(@Param("repoId") UUID repoId);

    @Modifying
    @Query("DELETE FROM CodeChunk c WHERE c.repo.id = :repoId AND c.filePath IN :filePaths")
    void deleteByRepoIdAndFilePaths(@Param("repoId") UUID repoId, @Param("filePaths") List<String> filePaths);

    int countByRepoId(UUID repoId);
}
```

### 4.4 ConversationRepository.java

```java
package com.codebaseqa.repository;

import com.codebaseqa.model.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    Page<Conversation> findByUserIdOrderByUpdatedAtDesc(UUID userId, Pageable pageable);
    Page<Conversation> findByUserIdAndRepoIdOrderByUpdatedAtDesc(UUID userId, UUID repoId, Pageable pageable);
    Optional<Conversation> findByIdAndUserId(UUID id, UUID userId);
}
```

### 4.5 MessageRepository.java

```java
package com.codebaseqa.repository;

import com.codebaseqa.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    // Get last N messages for conversation context
    List<Message> findTop10ByConversationIdOrderByCreatedAtDesc(UUID conversationId);
}
```

### 4.6 IndexingJobRepository.java

```java
package com.codebaseqa.repository;

import com.codebaseqa.model.IndexingJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IndexingJobRepository extends JpaRepository<IndexingJob, UUID> {
    Optional<IndexingJob> findTopByRepoIdOrderByCreatedAtDesc(UUID repoId);

    @Query("SELECT j FROM IndexingJob j WHERE j.status = 'PROCESSING' AND j.startedAt < :cutoff")
    List<IndexingJob> findStaleJobs(Instant cutoff);

    boolean existsByRepoIdAndStatusIn(UUID repoId, List<IndexingJob.JobStatus> statuses);
}
```
