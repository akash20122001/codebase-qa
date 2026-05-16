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
