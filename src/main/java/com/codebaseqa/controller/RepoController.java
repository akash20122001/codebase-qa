package com.codebaseqa.controller;

import com.codebaseqa.dto.request.ConnectRepoRequest;
import com.codebaseqa.dto.response.ApiResponse;
import com.codebaseqa.dto.response.ConnectRepoResponse;
import com.codebaseqa.dto.response.RepoResponse;
import com.codebaseqa.model.IndexingJob;
import com.codebaseqa.model.Repo;
import com.codebaseqa.model.User;
import com.codebaseqa.service.RepoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
@Slf4j
public class RepoController {

    private final RepoService repoService;

    /**
     * POST /api/repos
     * Connect a GitHub repository and trigger indexing.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ConnectRepoResponse>> connectRepo(
            @Valid @RequestBody ConnectRepoRequest request,
            @AuthenticationPrincipal User user) {

        log.info("=== POST /api/repos - Connect Repository ===");
        log.info("Request body: {}", request);
        log.info("Authenticated user: {}", user != null ? user.getUsername() : "NULL");
        log.info("User ID: {}", user != null ? user.getId() : "NULL");
        log.info("RepoFullName: {}", request.getRepoFullName());
        log.info("Branch: {}", request.getBranch());

        try {
            var result = repoService.connectRepo(request, user);
            log.info("Repository connected successfully: {}", result.repo().getFullName());

            ConnectRepoResponse response = ConnectRepoResponse.from(result.repo(), result.jobId());
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("Failed to connect repository", e);
            throw e;
        }
    }

    /**
     * GET /api/repos
     * List all connected repositories for the current user.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<RepoResponse>>> getUserRepos(
            @AuthenticationPrincipal User user) {

        log.info("User {} fetching repos", user.getUsername());

        List<Repo> repos = repoService.getUserRepos(user.getId());
        List<RepoResponse> response = repos.stream()
            .map(RepoResponse::from)
            .toList();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * GET /api/repos/{repoId}
     * Get details of a specific repository.
     */
    @GetMapping("/{repoId}")
    public ResponseEntity<ApiResponse<RepoResponse>> getRepo(
            @PathVariable UUID repoId,
            @AuthenticationPrincipal User user) {

        log.info("User {} fetching repo: {}", user.getUsername(), repoId);

        Repo repo = repoService.getRepo(repoId, user.getId());
        RepoResponse response = RepoResponse.from(repo);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * DELETE /api/repos/{repoId}
     * Disconnect a repository.
     */
    @DeleteMapping("/{repoId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> disconnectRepo(
            @PathVariable UUID repoId,
            @AuthenticationPrincipal User user) {

        log.info("User {} disconnecting repo: {}", user.getUsername(), repoId);

        repoService.disconnectRepo(repoId, user.getId());

        return ResponseEntity.ok(ApiResponse.success(
            Map.of("message", "Repository disconnected successfully")
        ));
    }

    /**
     * POST /api/repos/{repoId}/reindex
     * Manually trigger a full re-index of the repository.
     */
    @PostMapping("/{repoId}/reindex")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerReindex(
            @PathVariable UUID repoId,
            @AuthenticationPrincipal User user) {

        log.info("User {} triggering reindex for repo: {}", user.getUsername(), repoId);

        IndexingJob job = repoService.triggerReindex(repoId, user.getId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success(Map.of(
                "jobId", job.getId(),
                "status", job.getStatus().name()
            )));
    }
}
