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
