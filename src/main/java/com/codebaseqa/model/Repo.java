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
