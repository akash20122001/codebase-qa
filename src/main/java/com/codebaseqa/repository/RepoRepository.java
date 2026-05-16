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
