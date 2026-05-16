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
