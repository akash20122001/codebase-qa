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
